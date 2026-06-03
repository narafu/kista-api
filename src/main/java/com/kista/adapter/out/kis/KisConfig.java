package com.kista.adapter.out.kis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class KisConfig {

    @Bean
    public RestTemplate kisRestTemplate() {
        // KIS API 응답 지연 대비 타임아웃 설정 — 미설정 시 OS 기본값(~60초)로 무한 대기 가능
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000); // 연결 타임아웃 3초
        factory.setReadTimeout(7_000);    // 읽기 타임아웃 7초 (OAuth 토큰 발급 포함)
        return new RestTemplate(factory);
    }
}
