package com.kista.adapter.out.kis;

import com.kista.domain.port.out.KisConnectionTestPort;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class KisConnectionTestAdapter implements KisConnectionTestPort {

    private final RestTemplate kisRestTemplate;  // kisRestTemplate 빈명 일치 필수
    @Value("${kis.base-url}")
    private final String kisBaseUrl;

    @Override
    public boolean test(String appKey, String appSecret) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of(
                "grant_type", "client_credentials",
                "appkey", appKey,
                "appsecret", appSecret
        );
        try {
            // KIS OAuth 토큰 발급 시도 — 성공 시 true, 인증 실패/네트워크 오류 시 false
            kisRestTemplate.exchange(
                    kisBaseUrl + "/oauth2/tokenP",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class
            );
            return true;
        } catch (RestClientException e) {
            log.debug("KIS 연결 테스트 실패: {}", e.getMessage());
            return false;
        }
    }
}
