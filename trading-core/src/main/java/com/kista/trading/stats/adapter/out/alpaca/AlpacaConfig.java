package com.kista.trading.stats.adapter.out.alpaca;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

// marketcalendar 모듈(com.kista.marketcalendar.adapter.out.alpaca.AlpacaConfig, "marketAlpacaConfig")과
// stats 모듈(구 root com.kista.stats.adapter.out.alpaca.AlpacaConfig, 기본 빈 이름) 둘 다 이미 이 클래스명을
// 쓰고 있어(marketcalendar가 그 충돌 방지로 명시 이름 사용) 이 클래스도 명시적 이름 지정 필요
@Configuration("tradingStatsAlpacaConfig")
@EnableConfigurationProperties(AlpacaProperties.class)
class AlpacaConfig {

    @Bean
    public RestClient tradingStatsAlpacaRestClient() {
        return RestClient.builder().requestFactory(alpacaRequestFactory()).build();
    }

    static SimpleClientHttpRequestFactory alpacaRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000); // 연결 타임아웃 3초
        factory.setReadTimeout(7_000);    // 읽기 타임아웃 7초
        return factory;
    }
}
