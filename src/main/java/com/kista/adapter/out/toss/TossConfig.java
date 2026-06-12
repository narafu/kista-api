package com.kista.adapter.out.toss;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class TossConfig {

    @Bean
    public RestTemplate tossRestTemplate() {
        // Toss API 응답 지연 대비 타임아웃 설정
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000); // 연결 타임아웃 3초
        factory.setReadTimeout(10_000);   // 읽기 타임아웃 10초
        return new RestTemplate(factory);
    }
}
