package com.kista.adapter.out.toss;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class TossConfig {

    @Bean
    public RestClient tossRestClient() {
        return RestClient.builder().requestFactory(tossRequestFactory()).build();
    }

    // package-private — 필요 시 타임아웃 검증 테스트에서 직접 호출 가능
    static HttpComponentsClientHttpRequestFactory tossRequestFactory() {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(3))
                .setResponseTimeout(Timeout.ofSeconds(10))
                .build();
        var httpClient = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build();
        return new HttpComponentsClientHttpRequestFactory(httpClient);
    }
}
