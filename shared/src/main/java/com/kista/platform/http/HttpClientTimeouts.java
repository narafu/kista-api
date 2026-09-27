package com.kista.platform.http;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

// 외부 API 어댑터(Telegram/Alpaca/KB Land/CNN Fear&Greed 등) 공용 타임아웃 팩토리 —
// 미설정 시 OS 기본값(~60초)으로 무한 대기 가능해 어댑터마다 개별 구현하던 것을 통합
public final class HttpClientTimeouts {

    private HttpClientTimeouts() {}

    // 단순 GET/조회 위주 어댑터용 — JDK HttpURLConnection 기반
    public static SimpleClientHttpRequestFactory timeouts(int connectMs, int readMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectMs);
        factory.setReadTimeout(readMs);
        return factory;
    }

    // body가 있는 POST에서 401 등 오류 응답을 재시도해야 하는 어댑터(KIS/Toss 주문)용 —
    // HttpURLConnection은 스트리밍 모드(POST body 존재 시)에서 401을 즉시
    // HttpRetryException("cannot retry due to server authentication, in streaming mode")로 던져
    // HttpClientErrorException 캐치·토큰 재발급 재시도 로직에 도달하지 못한다.
    // java.net.http.HttpClient 기반 JdkClientHttpRequestFactory는 이 문제가 없다.
    public static ClientHttpRequestFactory jdkTimeouts(int connectMs, int readMs) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectMs))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readMs);
        return factory;
    }
}
