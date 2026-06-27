package com.kista.adapter.out.feargreed;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestTemplate;

@Configuration
class FearGreedConfig {

    @Bean
    RestTemplate fearGreedRestTemplate() {
        RestTemplate restTemplate = new RestTemplate();
        // CNN Cloudflare 봇 감지 우회 — User-Agent 외 Accept·Referer 등 브라우저 헤더 모방 필수
        restTemplate.getInterceptors().add((request, body, execution) -> {
            HttpHeaders headers = request.getHeaders();
            headers.set(HttpHeaders.USER_AGENT,
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36");
            headers.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");
            headers.set(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9");
            headers.set("Accept-Encoding", "gzip, deflate, br");
            headers.set(HttpHeaders.REFERER, "https://edition.cnn.com/markets/fear-and-greed");
            headers.set("Origin", "https://edition.cnn.com");
            return execution.execute(request, body);
        });
        return restTemplate;
    }
}
