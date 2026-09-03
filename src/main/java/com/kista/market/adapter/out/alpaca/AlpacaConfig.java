package com.kista.market.adapter.out.alpaca;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

// stats 모듈 com.kista.stats.adapter.out.alpaca.AlpacaConfig와 클래스명이 같아 빈 이름 충돌 방지용으로 명시적 이름 지정
@Configuration("marketAlpacaConfig")
@EnableConfigurationProperties(AlpacaProperties.class)
public class AlpacaConfig {

    // stats 모듈 alpacaRestClient 빈과 이름 충돌 방지 — 소비자(AlpacaCalendarAdapter) 필드명도 동일하게 맞춤
    @Bean
    public RestClient marketAlpacaRestClient() {
        return RestClient.builder().requestFactory(alpacaRequestFactory()).build();
    }

    // package-private — AlpacaConfigTest에서 타임아웃 검증용으로 직접 호출
    static SimpleClientHttpRequestFactory alpacaRequestFactory() {
        // Alpaca 마켓 달력 조회 API 응답 지연 대비 타임아웃 설정 — 미설정 시 OS 기본값(~60초)로 무한 대기 가능
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000); // 연결 타임아웃 3초
        factory.setReadTimeout(7_000);    // 읽기 타임아웃 7초
        return factory;
    }
}
