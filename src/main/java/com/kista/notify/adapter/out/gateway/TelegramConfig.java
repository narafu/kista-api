package com.kista.notify.adapter.out.gateway;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(TelegramProperties.class)
public class TelegramConfig {

    @Bean
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

    // package-private TelegramHttpClient를 Spring 빈으로 등록
    @Bean
    TelegramHttpClient telegramHttpClient(RestClient telegramRestClient) {
        return new TelegramHttpClient(telegramRestClient);
    }
}
