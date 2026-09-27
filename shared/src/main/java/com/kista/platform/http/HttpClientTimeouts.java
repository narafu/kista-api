package com.kista.platform.http;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

// 외부 API 어댑터(Telegram/Alpaca/KB Land/CNN Fear&Greed 등) 공용 타임아웃 팩토리 —
// 미설정 시 OS 기본값(~60초)으로 무한 대기 가능해 어댑터마다 개별 구현하던 것을 통합
public final class HttpClientTimeouts {

    private HttpClientTimeouts() {}

    public static SimpleClientHttpRequestFactory timeouts(int connectMs, int readMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectMs);
        factory.setReadTimeout(readMs);
        return factory;
    }
}
