package com.kista.benchmark.adapter.out.alpaca;

import com.kista.platform.http.RestClients;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AlpacaProperties.class)
public class AlpacaConfig {

    @Bean
    public RestClient alpacaRestClient() {
        return RestClients.withTimeouts(3_000, 7_000); // 연결 3초, 읽기 7초 — 미설정 시 OS 기본값(~60초)로 무한 대기 가능
    }
}
