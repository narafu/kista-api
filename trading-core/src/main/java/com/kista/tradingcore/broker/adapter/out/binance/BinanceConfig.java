package com.kista.tradingcore.broker.adapter.out.binance;

import com.kista.shared.platform.http.HttpClientTimeouts;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class BinanceConfig {

    // baseUrl 없음 — 서명된 절대 URI를 그대로 보내 템플릿 재인코딩(서명 불일치)을 피한다
    @Bean
    public RestClient binanceRestClient() {
        return RestClient.builder()
                .requestFactory(HttpClientTimeouts.jdkTimeouts(3_000, 10_000))
                .build();
    }
}
