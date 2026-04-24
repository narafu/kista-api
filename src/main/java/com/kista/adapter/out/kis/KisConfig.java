package com.kista.adapter.out.kis;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableConfigurationProperties(KisProperties.class)
public class KisConfig {

    @Bean
    public RestTemplate kisRestTemplate() {
        return new RestTemplate();
    }
}
