package com.kista.trading.stats.adapter.out.alpaca;

import org.springframework.boot.context.properties.ConfigurationProperties;

// marketcalendar의 동명 AlpacaProperties(com.kista.marketcalendar.adapter.out.alpaca)는 internal이라
// 모듈 밖에서 재사용 불가(ModulithArchitectureTest — non-exposed type 위반, 실측 확인) — trading
// stats 소유로 별도 선언. 같은 "alpaca" prefix를 공유해 값은 동일 API 키로 바인딩된다
@ConfigurationProperties(prefix = "alpaca")
public record AlpacaProperties(String baseUrl, String apiKey, String apiSecret, String dataBaseUrl) {}
