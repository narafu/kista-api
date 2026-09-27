package com.kista.broker.adapter.out.toss;

import com.kista.platform.http.HttpClientTimeouts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class TossConfig {

    // baseUrl을 빈 생성 시점에 고정 — 호출부는 상대 경로만 넘기고 URL 결합은 이 한 곳으로 통일
    // 타임아웃은 java.net.http.HttpClient 기반 공용 헬퍼(HttpClientTimeouts.jdkTimeouts) 재사용 —
    // httpclient5(Apache) 의존은 제거했지만, JDK HttpURLConnection 기반 SimpleClientHttpRequestFactory는
    // POST body 스트리밍 모드에서 401을 즉시 HttpRetryException으로 던져 TossHttpClient의 토큰 재발급
    // 재시도(executeWithBackoffRetry)에 도달하지 못한다 — jdkTimeouts(java.net.http.HttpClient)는 이 문제가 없다
    @Bean
    public RestClient tossRestClient(@Value("${toss.base-url}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(HttpClientTimeouts.jdkTimeouts(3_000, 10_000))
                .build();
    }
}
