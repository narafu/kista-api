package com.kista.tradingcore.stats.adapter.out.coinmarketcap;

import org.springframework.boot.context.properties.ConfigurationProperties;

// CoinMarketCap Pro API 설정 — 키가 비면 공식 API 호출은 실패한다
@ConfigurationProperties(prefix = "cmc")
public record CoinMarketCapProperties(String apiKey) {}
