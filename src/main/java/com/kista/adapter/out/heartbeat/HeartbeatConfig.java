package com.kista.adapter.out.heartbeat;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableConfigurationProperties(HeartbeatProperties.class)
class HeartbeatConfig {

    // 핑은 부가 기능 — 기존 TelegramConfig/AlpacaConfig와 동일하게 기본 RestTemplate 사용
    @Bean
    RestTemplate heartbeatRestTemplate() {
        return new RestTemplate();
    }
}
