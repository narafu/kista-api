package com.kista.stats.adapter.out.alpaca;

import com.kista.platform.http.HttpClientTimeouts;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AlpacaProperties.class)
public class AlpacaConfig {

    @Bean
    public RestClient alpacaRestClient() {
        return RestClient.builder().requestFactory(alpacaRequestFactory()).build();
    }

    // package-private — AlpacaConfigTest에서 타임아웃 검증용으로 직접 호출
    static SimpleClientHttpRequestFactory alpacaRequestFactory() {
        // Alpaca 마켓 달력 조회 API 응답 지연 대비 타임아웃 설정 — 미설정 시 OS 기본값(~60초)로 무한 대기 가능
        return HttpClientTimeouts.timeouts(3_000, 7_000); // 연결 3초, 읽기 7초
    }
}
