package com.kista.adapter.out.feargreed;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
class FearGreedConfig {

    @Bean
    RestTemplate fearGreedRestTemplate() {
        return new RestTemplate();
    }
}
