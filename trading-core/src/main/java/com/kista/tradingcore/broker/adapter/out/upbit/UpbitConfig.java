package com.kista.tradingcore.broker.adapter.out.upbit;

import com.kista.shared.platform.http.HttpClientTimeouts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class UpbitConfig {

    // baseUrl 고정 — 호출부는 상대 경로만. 타임아웃은 Toss와 같은 JDK HttpClient 공용 헬퍼
    @Bean
    public RestClient upbitRestClient(@Value("${upbit.base-url:https://api.upbit.com}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(HttpClientTimeouts.jdkTimeouts(3_000, 10_000))
                .build();
    }
}
