package com.kista.marketcalendar.adapter.out.alpaca;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "alpaca")
public record AlpacaProperties(String baseUrl, String apiKey, String apiSecret, String dataBaseUrl) {}
