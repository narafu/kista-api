package com.kista.notify.adapter.out.gateway;

import com.kista.platform.http.HttpClientTimeouts;
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
        return HttpClientTimeouts.timeouts(3_000, 7_000); // 연결 3초, 읽기 7초
    }

    // TelegramHttpClient를 Spring 빈으로 등록 (gateway 어댑터·adapter.in.telegram.TelegramBotService 공용)
    @Bean
    TelegramHttpClient telegramHttpClient(RestClient telegramRestClient) {
        return new TelegramHttpClient(telegramRestClient);
    }
}
