package com.kista.api.market.adapter.out.feargreed;

import com.kista.shared.platform.http.CnnRestClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
class FearGreedConfig {

    @Bean
    RestClient fearGreedRestClient() {
        // CNN Fear & Greed 지수 조회 API 응답 지연 대비 타임아웃 설정 — 미설정 시 OS 기본값(~60초)로 무한 대기 가능
        return CnnRestClients.withTimeouts(3_000, 7_000); // 연결 3초, 읽기 7초
    }
}
