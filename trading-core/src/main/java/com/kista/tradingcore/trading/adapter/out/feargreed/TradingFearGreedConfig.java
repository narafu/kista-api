package com.kista.tradingcore.trading.adapter.out.feargreed;

import com.kista.shared.platform.http.CnnRestClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

// CNN 공포탐욕지수 RestClient — 브라우저 헤더는 root FearGreedConfig와 CnnRestClients로 공유.
// 빈 이름을 root fearGreedRestClient(읽기 7초)와 다르게 둔다 — 한 컨텍스트에 올리면 같은 이름은 기동 실패, 소비자는 필드명으로 주입받는다
@Configuration
class TradingFearGreedConfig {

    @Bean
    RestClient tradingFearGreedRestClient() {
        return CnnRestClients.withTimeouts(3_000, 5_000); // 연결 3초, 읽기 5초 — 배치 지연 상한
    }
}
