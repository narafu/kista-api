package com.kista.trading.notify.adapter.out.gateway;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration("tradingTelegramConfig")
@EnableConfigurationProperties(TelegramProperties.class)
public class TelegramConfig {

    @Bean("tradingTelegramRestClient")
    public RestClient telegramRestClient() {
        return RestClient.builder().requestFactory(telegramRequestFactory()).build();
    }

    // package-private — 필요 시 타임아웃 검증 테스트에서 직접 호출 가능
    static SimpleClientHttpRequestFactory telegramRequestFactory() {
        // 텔레그램 API 응답 지연 대비 타임아웃 설정 — 미설정 시 OS 기본값으로 무한 대기 가능 (KisConfig와 동일 정책)
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000); // 연결 타임아웃 3초
        factory.setReadTimeout(7_000);    // 읽기 타임아웃 7초
        return factory;
    }

    // package-private TelegramHttpClient를 Spring 빈으로 등록.
    // 파라미터명을 실제 빈 이름(tradingTelegramRestClient)과 일치시켜 주입 — RestClient 빈이 이제
    // 여러 개(kis/toss/marketAlpaca/tradingStatsAlpaca/heartbeat/internalApi 등)라 타입 단독으로는
    // 모호(NoUniqueBeanDefinitionException). 예전엔 컨텍스트에 RestClient 빈이 하나뿐이라 파라미터명이
    // 무엇이든 무관하게 동작했으나, 4a Task 10에서 RestClient 빈이 늘며 실제로 깨진 것을 확인
    @Bean("tradingTelegramHttpClient")
    TelegramHttpClient telegramHttpClient(RestClient tradingTelegramRestClient) {
        return new TelegramHttpClient(tradingTelegramRestClient);
    }
}
