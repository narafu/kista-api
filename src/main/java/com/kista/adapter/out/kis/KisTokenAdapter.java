package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.port.out.KisTokenCachePort;
import com.kista.domain.port.out.KisTokenPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class KisTokenAdapter implements KisTokenPort {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul"); // KIS API 응답 시각대
    private static final DateTimeFormatter KIS_EXPIRY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"); // KIS 만료 시각 포맷

    // KisHttpClient를 주입받지 않음 — KisHttpClient가 KisTokenPort에 의존하므로 순환 방지
    private final RestTemplate kisRestTemplate;
    private final KisProperties kisProperties;
    private final KisTokenCachePort kisTokenCachePort; // 토큰 캐시 포트

    @Override
    public String getToken() {
        // 만료 1분 전부터 무효 처리 — 경계값 만료 오류(EGW00123) 방지
        Optional<String> cached = kisTokenCachePort.findValidToken(OffsetDateTime.now(KST).plusMinutes(1));
        if (cached.isPresent()) {
            return cached.get();
        }

        // 캐시 미스 — KIS API 신규 발급 후 저장
        return fetchAndCacheToken();
    }

    private String fetchAndCacheToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of(
                "grant_type", "client_credentials",
                "appkey", kisProperties.appKey(),
                "appsecret", kisProperties.appSecret()
        );

        // KIS OAuth 토큰 발급 요청 (RestTemplate 직접 호출 — KisHttpClient 경유 불가)
        TokenResponse response = kisRestTemplate.exchange(
                kisProperties.baseUrl() + "/oauth2/tokenP",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                TokenResponse.class
        ).getBody();

        OffsetDateTime expiresAt = parseExpiry(response.accessTokenExpired());

        // 발급된 토큰을 DB에 upsert
        kisTokenCachePort.saveToken(response.accessToken(), expiresAt);
        return response.accessToken();
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
}
