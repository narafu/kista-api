package com.kista.broker.adapter.out.kis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class KisConfig {

    @Bean
    public RestClient kisRestClient() {
        return RestClient.builder().requestFactory(kisRequestFactory()).build();
    }

    // package-private — 필요 시 타임아웃 검증 테스트에서 직접 호출 가능
    static SimpleClientHttpRequestFactory kisRequestFactory() {
        // KIS API 응답 지연 대비 타임아웃 설정 — 미설정 시 OS 기본값(~60초)로 무한 대기 가능
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000); // 연결 타임아웃 3초
        factory.setReadTimeout(7_000);    // 읽기 타임아웃 7초 (OAuth 토큰 발급 포함)
        return factory;
    }
}
