package com.kista.platform.http;

import org.springframework.web.client.RestClient;

// 외부 API 어댑터 공용 RestClient 팩토리 — 타임아웃(+선택 baseUrl)만 다른 Config 클래스들의 빌더 반복을 통합
public final class RestClients {

    private RestClients() {}

    // 타임아웃만 지정한 RestClient (URL은 호출부가 절대 경로로 조립)
    public static RestClient withTimeouts(int connectMs, int readMs) {
        return RestClient.builder().requestFactory(HttpClientTimeouts.timeouts(connectMs, readMs)).build();
    }

    // baseUrl을 고정한 RestClient — 호출부는 상대 경로만 넘기고 URL 결합은 Config 한 곳으로 통일
    public static RestClient withTimeouts(String baseUrl, int connectMs, int readMs) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(HttpClientTimeouts.timeouts(connectMs, readMs))
                .build();
    }
}
