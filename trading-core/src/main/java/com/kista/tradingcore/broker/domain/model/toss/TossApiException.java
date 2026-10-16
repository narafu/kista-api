package com.kista.tradingcore.broker.domain.model.toss;

import com.kista.tradingcore.broker.domain.model.BrokerApiException;

// Toss 외부 API 호출 실패 — BrokerApiException으로 503 매핑됨
public class TossApiException extends BrokerApiException {

    public TossApiException(String message, Throwable cause) {
        this(message, cause, Conflict.NONE);
    }

    public TossApiException(String message, Throwable cause, Conflict conflict) {
        super("Toss", message, cause, conflict);
    }
}
