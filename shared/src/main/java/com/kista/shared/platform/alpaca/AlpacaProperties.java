package com.kista.shared.platform.alpaca;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Alpaca 접속 설정 한 벌 — trading-core의 휴장일·백테스트 일봉·공용 시세 어댑터가 함께 쓴다(모듈마다 따로 선언하지 않는다)
@ConfigurationProperties(prefix = "alpaca")
public record AlpacaProperties(
        String baseUrl,     // 거래 API(휴장일 캘린더)
        String apiKey,      // APCA-API-KEY-ID
        String apiSecret,   // APCA-API-SECRET-KEY
        String dataBaseUrl  // 시세 API(일봉·스냅샷)
) {}
