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
        // CNN이 User-Agent 없는 요청을 418로 차단하므로 브라우저처럼 위장
        restTemplate.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().set(HttpHeaders.USER_AGENT,
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36");
            return execution.execute(request, body);
        });
        return restTemplate;
    }
}
