package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.adapter.out.broker.DoubleCheckedTokenCache;
import com.kista.common.TimeZones;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.KisApiException;
import com.kista.domain.port.out.BrokerTokenCachePort;
import com.kista.domain.port.out.KisConnectionTestPort;
import com.kista.domain.port.out.KisTokenPort;
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

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// KisTokenPort + KisConnectionTestPort 통합 구현체
// OAuth 호출은 RestTemplate 직접 사용 — KisHttpClient 미사용(순환 의존 회피)
@Slf4j
@Component
@RequiredArgsConstructor
public class KisAuthApi implements KisTokenPort, KisConnectionTestPort {

    private static final ZoneId KST = TimeZones.KST;
    private static final DateTimeFormatter KIS_EXPIRY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // 계좌별 토큰 발급 락 템플릿 — 동시 요청 시 중복 발급 방지 (KIS/Toss 공용)
    private final DoubleCheckedTokenCache tokenCache = new DoubleCheckedTokenCache();

    // 연결 테스트 직후 계좌번호 검증 시 재발급 방지 — KIS EGW00133(1분당 1회 제한) 우회용 단기 캐시
    private final ConcurrentHashMap<String, TempTokenEntry> tempTokenCache = new ConcurrentHashMap<>();
    private record TempTokenEntry(String token, Instant expiresAt) {}

    private final RestTemplate kisRestTemplate;
    private final BrokerTokenCachePort brokerTokenCachePort;
    @Value("${kis.base-url}")
    private final String kisBaseUrl;

    // ── KisTokenPort ───────────────────────────────────────────────────────────

    @Override
    public String getToken(UUID accountId, String appKey, String appSecret) {
        return tokenCache.getOrFetch(brokerTokenCachePort, accountId, this::threshold,
                () -> fetchAndCacheToken(accountId, appKey, appSecret));
    }

    private String fetchAndCacheToken(UUID accountId, String appKey, String appSecret) {
        log.info("KIS 토큰 신규 발급: accountId={}", accountId);
        try {
            TokenResponse response = issueOAuthToken(appKey, appSecret);
            OffsetDateTime expiresAt = parseExpiry(response.accessTokenExpired());
            // 발급된 토큰을 account_id 기준으로 DB에 upsert
            brokerTokenCachePort.saveToken(accountId, response.accessToken(), expiresAt);
            return response.accessToken();
        } catch (Account.InvalidKisKeyException e) {
            throw e; // KIS 키 검증 실패는 그대로 전파
        } catch (Exception e) {
            throw new KisApiException("KIS 토큰 발급 실패 accountId=" + accountId, e);
        }
    }

    // 만료 1분 전부터 무효 처리 — 경계값 만료 오류(EGW00123) 방지
    private OffsetDateTime threshold() {
        return OffsetDateTime.now(KST).plusMinutes(1);
    }

    // ── KisConnectionTestPort ──────────────────────────────────────────────────

    @Override
    public void test(String appKey, String appSecret, UUID accountId) {
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
            TokenResponse response = issueOAuthToken(appKey, appSecret);
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
            throw new Account.InvalidKisKeyException();
        }
    }

    @Override
    public void testAccountNo(String appKey, String appSecret, String accountNo) {
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
                token = issueOAuthToken(appKey, appSecret).accessToken();
            } catch (HttpStatusCodeException e) {
                throw kisKeyException(e, "계좌번호 검증 중 토큰 발급");
            } catch (RestClientException e) {
                log.debug("계좌번호 검증 중 토큰 발급 실패: {}", e.getMessage());
                throw new Account.InvalidKisKeyException();
            }
        }

        // 2단계: TTTC2101R 호출로 계좌번호 유효성 검증
        String[] parts = KisHttpClient.splitAccountNo(accountNo);
        HttpHeaders headers = KisHttpClient.buildHeaders(token, appKey, appSecret, KisTradingApi.MARGIN_TR_ID);

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
                throw new Account.InvalidKisKeyException();
            }
        } catch (RestClientException e) {
            log.debug("계좌번호 검증 실패: {}", e.getMessage());
            throw new Account.InvalidKisKeyException();
        }
    }

    // EGW00133: KIS 1분당 1회 발급 제한 초과 → KisRateLimitException, 그 외 → InvalidKisKeyException
    private RuntimeException kisKeyException(HttpStatusCodeException e, String context) {
        if (e.getResponseBodyAsString().contains("EGW00133")) {
            log.debug("{} rate limit (EGW00133)", context);
            return new Account.KisRateLimitException();
        }
        log.debug("{} 실패: {}", context, e.getMessage());
        return new Account.InvalidKisKeyException();
    }

    // KIS OAuth 토큰 발급 — getToken/test/testAccountNo 공용
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
        if (response == null) throw new Account.InvalidKisKeyException();
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
