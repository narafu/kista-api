package com.kista.tradingcore.broker.domain.model;

// 공용 시세(MarketDataClient) 호출 실패 — BrokerApiException으로 503 매핑됨. 값이 없는 것(미존재)은 이 예외가 아니다
public class MarketDataApiException extends BrokerApiException {

    public MarketDataApiException(String message, Throwable cause) {
        super("시세", message, cause); // 응답 title "시세 API Error"
    }
}
