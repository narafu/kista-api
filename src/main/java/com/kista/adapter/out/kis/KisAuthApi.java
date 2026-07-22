package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.adapter.out.broker.TokenCoordinator;
import com.kista.common.TimeZones;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.KisApiException;
import com.kista.domain.port.out.BrokerTokenCachePort;
import com.kista.domain.port.out.broker.BrokerConnectionTestPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// BrokerConnectionTestPort 구현체 — getToken/recoverToken은 KisHttpClient에 직접 주입되는 구체 메서드
// OAuth 호출은 RestTemplate 직접 사용 — KisHttpClient 미사용(순환 의존 회피)
@Slf4j
@Component
@RequiredArgsConstructor
class KisAuthApi implements BrokerConnectionTestPort {

    private static final ZoneId KST = TimeZones.KST;
    private static final DateTimeFormatter KIS_EXPIRY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // 연결 테스트 직후 계좌번호 검증 시 재발급 방지 — KIS EGW00133(1분당 1회 제한) 우회용 단기 캐시
    private final ConcurrentHashMap<String, TempTokenEntry> tempTokenCache = new ConcurrentHashMap<>();
    private record TempTokenEntry(String token, Instant expiresAt) {}

    private final RestTemplate kisRestTemplate;
    // verifyCredentials/verifyAccount(계좌 등록 전, accountId 없을 수 있음) 전용 — 코디네이터를 거치지 않는 단순 캐시 접근
    private final BrokerTokenCachePort brokerTokenCachePort;
    // getToken/recoverToken 전용 — JVM-local 더블체크락 + DB 캐시 조정 (Toss는 별도 Redis 분산 구현체).
    // TokenCoordinator를 구현하는 빈이 2개(KIS/Toss)라 인터페이스로 주입하면 Spring이 모호해짐 —
    // TossAuthApi가 TossDistributedTokenCoordinator를 구체 타입으로 주입받는 것과 동일하게 구체 타입 사용
    private final KisTokenCoordinator tokenCoordinator;
    @Value("${kis.base-url}")
    private final String kisBaseUrl;

    // ── 토큰 발급 / 401 복구 — KisHttpClient가 구체 타입으로 직접 주입 ──────────────

    public String getToken(UUID accountId, String appKey, String appSecret) {
        return tokenCoordinator.obtain(accountId, () -> issueAccountToken(accountId, appKey, appSecret));
    }

    public TokenCoordinator.RecoveredToken recoverToken(
            UUID accountId, String appKey, String appSecret, String rejectedAccessToken) {
        return tokenCoordinator.recover(accountId, rejectedAccessToken,
                () -> issueAccountToken(accountId, appKey, appSecret));
    }

    private TokenCoordinator.IssuedToken issueAccountToken(UUID accountId, String appKey, String appSecret) {
        log.info("KIS 토큰 신규 발급: accountId={}", accountId);
        try {
            TokenResponse response = issueOAuthToken(appKey, appSecret);
            OffsetDateTime expiresAt = parseExpiry(response.accessTokenExpired());
            long expiresInSeconds = Duration.between(OffsetDateTime.now(KST), expiresAt).getSeconds();
            return new TokenCoordinator.IssuedToken(response.accessToken(), expiresInSeconds);
        } catch (Account.InvalidBrokerKeyException e) {
            throw e; // 증권사 키 검증 실패는 그대로 전파
        } catch (Exception e) {
            throw new KisApiException("KIS 토큰 발급 실패 accountId=" + accountId, e);
        }
    }

    // ── BrokerConnectionTestPort ───────────────────────────────────────────────

    @Override
    public Account.Broker supports() {
        return Account.Broker.KIS;
    }

    @Override
    public void verifyCredentials(String appKey, String secretKey, UUID accountId) {
        // 유효 캐시 토큰이 있으면 재발급 없이 성공 — KIS 발급 알림 및 횟수 제한 방지
        if (accountId != null) {
            Optional<String> cached = brokerTokenCachePort.findValidToken(accountId, OffsetDateTime.now(KST).plusMinutes(1));
            if (cached.isPresent()) {
                log.debug("KIS 연결 테스트: 캐시 토큰 유효 — KIS 호출 생략 (accountId={})", accountId);
                return;
            }
        }
        try {
            // 캐시 미스 시 KIS OAuth 호출 — 성공 시 accountId 있으면 영구 캐시, null이면 단기 캐시 저장
            TokenResponse response = issueOAuthToken(appKey, secretKey);
            if (accountId != null) {
                // 계좌 ID가 있으면 발급된 토큰을 캐시에 저장 — 직후 실 API 호출 시 재발급 방지
                OffsetDateTime expiresAt = parseExpiry(response.accessTokenExpired());
                brokerTokenCachePort.saveToken(accountId, response.accessToken(), expiresAt);
            } else {
                // accountId 없음(등록 전) — 단기 캐시에 저장해 계좌번호 검증 시 재발급 방지 (EGW00133)
                tempTokenCache.put(appKey, new TempTokenEntry(response.accessToken(), Instant.now().plusSeconds(90)));
            }
        } catch (HttpStatusCodeException e) {
            throw kisKeyException(e, "KIS 연결 테스트");
        } catch (RestClientException e) {
            log.debug("KIS 연결 테스트 실패: {}", e.getMessage());
            throw new Account.InvalidBrokerKeyException();
        }
    }

    @Override
    public String verifyAccount(String appKey, String secretKey, String accountNo) {
        // 1단계: 토큰 확보 — 연결 테스트 단기 캐시 우선, 없으면 신규 발급 (EGW00133 방지)
        String token;
        Instant now = Instant.now();
        TempTokenEntry cached = tempTokenCache.get(appKey);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            log.debug("계좌번호 검증: 단기 캐시 토큰 재사용 (appKey={}...)", appKey.substring(0, Math.min(8, appKey.length())));
            token = cached.token();
        } else {
            // 만료된 항목 즉시 제거 — ConcurrentHashMap 누적 방지
            if (cached != null) tempTokenCache.remove(appKey, cached);
            try {
                token = issueOAuthToken(appKey, secretKey).accessToken();
            } catch (HttpStatusCodeException e) {
                throw kisKeyException(e, "계좌번호 검증 중 토큰 발급");
            } catch (RestClientException e) {
                log.debug("계좌번호 검증 중 토큰 발급 실패: {}", e.getMessage());
                throw new Account.InvalidBrokerKeyException();
            }
        }

        // 2단계: TTTC2101R 호출로 계좌번호 유효성 검증
        String[] parts = KisHttpClient.splitAccountNo(accountNo);
        HttpHeaders headers = KisHttpClient.buildHeaders(token, appKey, secretKey, KisTradingApi.MARGIN_TR_ID);

        String url = UriComponentsBuilder
                .fromUriString(kisBaseUrl + KisTradingApi.MARGIN_PATH)
                .queryParam("CANO", parts[0])
                .queryParam("ACNT_PRDT_CD", parts[1])
                .toUriString();

        try {
            AccountNoCheckResponse response = kisRestTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), AccountNoCheckResponse.class
            ).getBody();
            if (response == null || !"0".equals(response.rtCd())) {
                // rt_cd != "0" = 계좌번호 불일치 또는 KIS 오류 → 422
                log.debug("계좌번호 검증 실패: rt_cd={}, msg={}", response != null ? response.rtCd() : "null", response != null ? response.msg1() : "null");
                throw new Account.InvalidBrokerKeyException();
            }
        } catch (RestClientException e) {
            log.debug("계좌번호 검증 실패: {}", e.getMessage());
            throw new Account.InvalidBrokerKeyException();
        }
        return null; // KIS: brokerAccountCode 없음 (accountNo에 통합)
    }

    // EGW00133: KIS 1분당 1회 발급 제한 초과 → KisRateLimitException, 그 외 → InvalidBrokerKeyException
    private RuntimeException kisKeyException(HttpStatusCodeException e, String context) {
        if (e.getResponseBodyAsString().contains("EGW00133")) {
            log.debug("{} rate limit (EGW00133)", context);
            return new Account.KisRateLimitException();
        }
        log.debug("{} 실패: {}", context, e.getMessage());
        return new Account.InvalidBrokerKeyException();
    }

    // KIS OAuth 토큰 발급 — getToken/verifyCredentials/verifyAccount 공용
    private TokenResponse issueOAuthToken(String appKey, String appSecret) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of(
                "grant_type", "client_credentials",
                "appkey", appKey,
                "appsecret", appSecret
        );
        TokenResponse response = kisRestTemplate.exchange(
                kisBaseUrl + "/oauth2/tokenP",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                TokenResponse.class
        ).getBody();
        if (response == null) throw new Account.InvalidBrokerKeyException();
        return response;
    }

    OffsetDateTime parseExpiry(String raw) {
        // KST 문자열 "yyyy-MM-dd HH:mm:ss" → ZonedDateTime → OffsetDateTime(+09:00)
        LocalDateTime ldt = LocalDateTime.parse(raw, KIS_EXPIRY_FORMAT);
        return ldt.atZone(KST).toOffsetDateTime();
    }

    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("access_token_token_expired") String accessTokenExpired
    ) {}

    record AccountNoCheckResponse(
            @JsonProperty("rt_cd") String rtCd,   // "0" = 성공, "1" = 오류
            @JsonProperty("msg1") String msg1      // 오류 메시지
    ) {}
}
