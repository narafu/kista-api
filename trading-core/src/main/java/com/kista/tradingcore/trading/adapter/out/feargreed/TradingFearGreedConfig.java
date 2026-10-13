package com.kista.tradingcore.trading.adapter.out.feargreed;

import com.kista.shared.platform.http.HttpClientTimeouts;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

// CNN 공포탐욕지수 RestClient — root FearGreedConfig와 같은 헤더(프로세스가 달라 복제, spec 6.2)
@Configuration
class TradingFearGreedConfig {

    @Bean
    RestClient fearGreedRestClient() {
        return RestClient.builder()
                .requestFactory(HttpClientTimeouts.timeouts(3_000, 5_000)) // 연결 3초, 읽기 5초 — 배치 지연 상한
                .requestInterceptor(fearGreedHeaderInterceptor())
                .build();
    }

    // package-private — CnnFearGreedIndexAdapterTest에서 재사용
    static ClientHttpRequestInterceptor fearGreedHeaderInterceptor() {
        // CNN Cloudflare 봇 감지 우회 — 브라우저 헤더 모방, Accept-Encoding은 강제하지 않는다(자동 해제 실패 방지)
        return (request, body, execution) -> {
            HttpHeaders headers = request.getHeaders();
            headers.set(HttpHeaders.USER_AGENT,
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36");
            headers.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");
            headers.set(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9");
            headers.set(HttpHeaders.REFERER, "https://edition.cnn.com/markets/fear-and-greed");
            headers.set("Origin", "https://edition.cnn.com");
            return execution.execute(request, body);
        };
    }
}
