package com.kista.adapter.out.notify;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableConfigurationProperties(TelegramProperties.class)
public class TelegramConfig {

    @Bean
    public RestTemplate telegramRestTemplate() {
        return new RestTemplate();
    }

    // package-private TelegramHttpClient를 Spring 빈으로 등록
    @Bean
    TelegramHttpClient telegramHttpClient(RestTemplate telegramRestTemplate) {
        return new TelegramHttpClient(telegramRestTemplate);
    }
}
