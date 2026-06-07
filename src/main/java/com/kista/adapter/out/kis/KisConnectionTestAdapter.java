package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.domain.port.out.KisConnectionTestPort;
import com.kista.domain.port.out.KisTokenCachePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class KisConnectionTestAdapter implements KisConnectionTestPort {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter KIS_EXPIRY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RestTemplate kisRestTemplate;
    private final KisTokenCachePort kisTokenCachePort;
    @Value("${kis.base-url}")
    private final String kisBaseUrl;

    // 연결 테스트 직후 계좌번호 검증 시 재발급 방지 — KIS EGW00133(1분당 1회 제한) 우회용 단기 캐시
    private final ConcurrentHashMap<String, TempTokenEntry> tempTokenCache = new ConcurrentHashMap<>();
    private record TempTokenEntry(String token, Instant expiresAt) {}

    @Override
    public boolean test(String appKey, String appSecret, UUID accountId) {
        // 유효 캐시 토큰이 있으면 재발급 없이 성공 — KIS 발급 알림 및 횟수 제한 방지
        if (accountId != null) {
            Optional<String> cached = kisTokenCachePort.findValidToken(accountId, OffsetDateTime.now(KST).plusMinutes(1));
            if (cached.isPresent()) {
                log.debug("KIS 연결 테스트: 캐시 토큰 유효 — KIS 호출 생략 (accountId={})", accountId);
                return true;
            }
        }
        try {
            // 캐시 미스 시 KIS OAuth 호출 — 성공 시 accountId 있으면 영구 캐시, null이면 단기 캐시 저장
            TokenCheckResponse response = issueToken(appKey, appSecret);
            if (response != null) {
                if (accountId != null) {
                    // 계좌 ID가 있으면 발급된 토큰을 캐시에 저장 — 직후 실 API 호출 시 재발급 방지
                    OffsetDateTime expiresAt = parseExpiry(response.accessTokenExpired());
                    kisTokenCachePort.saveToken(accountId, response.accessToken(), expiresAt);
                } else {
                    // accountId 없음(등록 전) — 단기 캐시에 저장해 계좌번호 검증 시 재발급 방지 (EGW00133)
                    tempTokenCache.put(appKey, new TempTokenEntry(response.accessToken(), Instant.now().plusSeconds(90)));
                }
            }
            return true;
        } catch (RestClientException e) {
            log.debug("KIS 연결 테스트 실패: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void testAccountNo(String appKey, String appSecret, String accountNo) {
        // 1단계: 토큰 확보 — 연결 테스트 단기 캐시 우선, 없으면 신규 발급 (EGW00133 방지)
        String token;
        TempTokenEntry cached = tempTokenCache.get(appKey);
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            log.debug("계좌번호 검증: 단기 캐시 토큰 재사용 (appKey={}...)", appKey.substring(0, Math.min(8, appKey.length())));
            token = cached.token();
        } else {
            try {
                TokenCheckResponse tokenResponse = issueToken(appKey, appSecret);
                if (tokenResponse == null) throw new Account.InvalidKisKeyException();
                token = tokenResponse.accessToken();
            } catch (RestClientException e) {
                log.debug("계좌번호 검증 중 토큰 발급 실패: {}", e.getMessage());
                throw new Account.InvalidKisKeyException();
            }
        }

        // 2단계: TTTC2101R 호출로 계좌번호 유효성 검증
        HttpHeaders headers = new HttpHeaders();
        headers.set("authorization", "Bearer " + token);
        headers.set("appkey", appKey);
        headers.set("appsecret", appSecret);
        headers.set("tr_id", "TTTC2101R"); // 해외증거금 통화별조회
        headers.set("custtype", "P");

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("CANO", accountNo);
        params.add("ACNT_PRDT_CD", "01");

        String url = UriComponentsBuilder
                .fromUriString(kisBaseUrl + "/uapi/overseas-stock/v1/trading/foreign-margin")
                .queryParams(params)
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

    // KIS OAuth 토큰 발급 — test/testAccountNo 공용
    private TokenCheckResponse issueToken(String appKey, String appSecret) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of(
                "grant_type", "client_credentials",
                "appkey", appKey,
                "appsecret", appSecret
        );
        return kisRestTemplate.exchange(
                kisBaseUrl + "/oauth2/tokenP",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                TokenCheckResponse.class
        ).getBody();
    }

    private OffsetDateTime parseExpiry(String raw) {
        LocalDateTime ldt = LocalDateTime.parse(raw, KIS_EXPIRY_FORMAT);
        return ldt.atZone(KST).toOffsetDateTime();
    }

    record TokenCheckResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("access_token_token_expired") String accessTokenExpired
    ) {}

    record AccountNoCheckResponse(
            @JsonProperty("rt_cd") String rtCd,   // "0" = 성공, "1" = 오류
            @JsonProperty("msg1") String msg1      // 오류 메시지
    ) {}
}
