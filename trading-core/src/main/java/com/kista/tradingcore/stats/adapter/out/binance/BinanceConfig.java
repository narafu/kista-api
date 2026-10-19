package com.kista.tradingcore.stats.adapter.out.binance;

import com.kista.shared.platform.http.RestClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

// 다른 모듈의 *Config와 기본 빈 이름 충돌을 피하려 명시적 이름 지정
@Configuration("statsBinanceConfig")
class BinanceConfig {

    @Bean
    public RestClient statsBinanceRestClient() {
        return RestClients.withTimeouts(3_000, 7_000); // 연결 3초, 읽기 7초
    }
}
