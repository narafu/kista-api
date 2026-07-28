package com.kista.adapter.out.notify;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableConfigurationProperties(TelegramProperties.class)
public class TelegramConfig {

    @Bean
    public RestTemplate telegramRestTemplate() {
        // 텔레그램 API 응답 지연 대비 타임아웃 설정 — 미설정 시 OS 기본값으로 무한 대기 가능 (KisConfig와 동일 정책)
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000); // 연결 타임아웃 3초
        factory.setReadTimeout(7_000);    // 읽기 타임아웃 7초
        return new RestTemplate(factory);
    }

    // package-private TelegramHttpClient를 Spring 빈으로 등록
    @Bean
    TelegramHttpClient telegramHttpClient(RestTemplate telegramRestTemplate) {
        return new TelegramHttpClient(telegramRestTemplate);
    }
}
