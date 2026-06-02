package com.kista.adapter.out.alpaca;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableConfigurationProperties(AlpacaProperties.class)
public class AlpacaConfig {

    @Bean
    public RestTemplate alpacaRestTemplate() {
        return new RestTemplate();
    }
}
