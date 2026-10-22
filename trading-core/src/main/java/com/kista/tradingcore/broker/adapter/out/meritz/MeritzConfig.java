package com.kista.tradingcore.broker.adapter.out.meritz;

import com.kista.shared.platform.http.HttpClientTimeouts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class MeritzConfig {

    // baseUrl 고정 — 호출부는 상대 경로만. 오류 본문도 exchange()로 직접 읽으므로 상태코드 핸들러 불필요
    @Bean
    public RestClient meritzRestClient(@Value("${meritz.base-url:https://openapi.imeritz.com:9443}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(HttpClientTimeouts.jdkTimeouts(3_000, 10_000))
                .build();
    }
}
