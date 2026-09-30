package com.kista.tradingstats.adapter.out.alpaca;

import com.kista.platform.http.RestClients;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

// marketcalendar 모듈(com.kista.marketcalendar.adapter.out.alpaca.AlpacaConfig, "marketAlpacaConfig")과
// benchmark 모듈(구 root com.kista.benchmark.adapter.out.alpaca.AlpacaConfig, 기본 빈 이름) 둘 다 이미 이 클래스명을
// 쓰고 있어(marketcalendar가 그 충돌 방지로 명시 이름 사용) 이 클래스도 명시적 이름 지정 필요
@Configuration("tradingStatsAlpacaConfig")
@EnableConfigurationProperties(AlpacaProperties.class)
class AlpacaConfig {

    @Bean
    public RestClient tradingStatsAlpacaRestClient() {
        return RestClients.withTimeouts(3_000, 7_000); // 연결 3초, 읽기 7초
    }
}
