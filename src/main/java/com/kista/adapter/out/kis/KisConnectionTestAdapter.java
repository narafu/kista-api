package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
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
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of(
                "grant_type", "client_credentials",
                "appkey", appKey,
                "appsecret", appSecret
        );
        try {
            // 캐시 미스 시 KIS OAuth 호출 — 성공 시 accountId 있으면 캐시 저장
            TokenCheckResponse response = kisRestTemplate.exchange(
                    kisBaseUrl + "/oauth2/tokenP",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    TokenCheckResponse.class
            ).getBody();
            if (accountId != null && response != null) {
                // 계좌 ID가 있으면 발급된 토큰을 캐시에 저장 — 직후 실 API 호출 시 재발급 방지
                OffsetDateTime expiresAt = parseExpiry(response.accessTokenExpired());
                kisTokenCachePort.saveToken(accountId, response.accessToken(), expiresAt);
            }
            return true;
        } catch (RestClientException e) {
            log.debug("KIS 연결 테스트 실패: {}", e.getMessage());
            return false;
        }
    }

    private OffsetDateTime parseExpiry(String raw) {
        LocalDateTime ldt = LocalDateTime.parse(raw, KIS_EXPIRY_FORMAT);
        return ldt.atZone(KST).toOffsetDateTime();
    }

    record TokenCheckResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("access_token_token_expired") String accessTokenExpired
    ) {}
}
