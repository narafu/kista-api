package com.kista.trading.adapter.out.heartbeat;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(HeartbeatProperties.class)
class HeartbeatConfig {

    // 핑은 부가 기능 — 기존 TelegramConfig/AlpacaConfig와 동일하게 기본 RestClient 사용
    @Bean
    RestClient heartbeatRestClient() {
        return RestClient.builder().build();
    }
}
