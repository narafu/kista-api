package com.kista.adapter.out.feargreed;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
class FearGreedConfig {

    @Bean
    RestTemplate fearGreedRestTemplate() {
        // CNN Fear & Greed 지수 조회 API 응답 지연 대비 타임아웃 설정 — 미설정 시 OS 기본값(~60초)로 무한 대기 가능
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000); // 연결 타임아웃 3초
        factory.setReadTimeout(7_000);    // 읽기 타임아웃 7초
        RestTemplate restTemplate = new RestTemplate(factory);
        // CNN Cloudflare 봇 감지 우회 — User-Agent 외 Accept·Referer 등 브라우저 헤더 모방 필수
        restTemplate.getInterceptors().add((request, body, execution) -> {
            HttpHeaders headers = request.getHeaders();
            headers.set(HttpHeaders.USER_AGENT,
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36");
            headers.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");
            headers.set(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9");
            // 압축 인코딩을 강제하면 일부 응답이 자동 해제되지 않아 JSON 파싱이 깨질 수 있다.
            headers.set(HttpHeaders.REFERER, "https://edition.cnn.com/markets/fear-and-greed");
            headers.set("Origin", "https://edition.cnn.com");
            return execution.execute(request, body);
        });
        return restTemplate;
    }
}
