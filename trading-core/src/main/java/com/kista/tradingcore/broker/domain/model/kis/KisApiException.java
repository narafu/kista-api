package com.kista.tradingcore.broker.domain.model.kis;

import com.kista.tradingcore.broker.domain.model.BrokerApiException;

// KIS 외부 API 호출 실패를 나타내는 도메인 예외 — BrokerApiException으로 503 매핑됨
public class KisApiException extends BrokerApiException {
    public KisApiException(String message, Throwable cause) {
        super("KIS", message, cause, Conflict.NONE);
    }
}
