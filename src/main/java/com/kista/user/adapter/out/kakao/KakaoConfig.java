package com.kista.user.adapter.out.kakao;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(KakaoProperties.class)
public class KakaoConfig {
    @Bean
    public RestClient kakaoRestClient() {
        return RestClient.builder().build();
    }
}
