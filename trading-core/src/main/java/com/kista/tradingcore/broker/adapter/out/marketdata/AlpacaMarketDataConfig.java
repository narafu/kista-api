package com.kista.tradingcore.broker.adapter.out.marketdata;

import com.kista.shared.platform.alpaca.AlpacaProperties;
import com.kista.shared.platform.http.RestClients;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AlpacaProperties.class)
class AlpacaMarketDataConfig {

    // 시세 API baseUrl 고정 — 어댑터는 상대 경로만 넘긴다. 빈 이름은 marketcalendar·stats의 Alpaca RestClient와 겹치지 않게 한다
    @Bean
    RestClient marketDataAlpacaRestClient(AlpacaProperties alpacaProperties) {
        return RestClients.withTimeouts(alpacaProperties.dataBaseUrl(), 3_000, 7_000); // 연결 3초, 읽기 7초
    }
}
