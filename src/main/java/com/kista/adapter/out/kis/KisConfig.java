package com.kista.adapter.out.kis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class KisConfig {

    @Bean
    public RestTemplate kisRestTemplate() {
        return new RestTemplate();
    }
}
