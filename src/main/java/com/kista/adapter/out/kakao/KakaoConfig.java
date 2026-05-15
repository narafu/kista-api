package com.kista.adapter.out.kakao;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableConfigurationProperties(KakaoProperties.class)
public class KakaoConfig {
    @Bean
    public RestTemplate kakaoRestTemplate() {
        return new RestTemplate();
    }
}
