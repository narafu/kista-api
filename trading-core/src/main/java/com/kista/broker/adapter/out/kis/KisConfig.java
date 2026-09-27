package com.kista.broker.adapter.out.kis;

import com.kista.platform.http.HttpClientTimeouts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class KisConfig {

    // baseUrl을 빈 생성 시점에 고정 — 호출부는 상대 경로만 넘기고 URL 결합은 이 한 곳으로 통일
    // 타임아웃은 TossConfig와 동일 공용 헬퍼(HttpClientTimeouts.jdkTimeouts) 재사용 — POST body가 있는
    // 주문/취소 요청에서 401을 받았을 때 SimpleClientHttpRequestFactory(HttpURLConnection)는 스트리밍
    // 모드라 즉시 HttpRetryException을 던져 KisHttpClient의 토큰 재발급 재시도에 도달하지 못한다
    @Bean
    public RestClient kisRestClient(@Value("${kis.base-url}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(HttpClientTimeouts.jdkTimeouts(3_000, 7_000)) // 읽기 타임아웃 7초 (OAuth 토큰 발급 포함)
                .build();
    }
}
