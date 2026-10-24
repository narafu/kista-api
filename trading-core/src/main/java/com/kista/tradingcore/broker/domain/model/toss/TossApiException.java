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

    public TossApiException(String message, Throwable cause, Conflict conflict, boolean rejected) {
        super("Toss", message, cause, conflict, rejected);
    }

    public TossApiException(String message, Throwable cause, Conflict conflict, boolean rejected, boolean insufficientFunds) {
        super("Toss", message, cause, conflict, rejected, insufficientFunds);
    }

    // 증권사가 접수하지 않았음이 확실한 실패(송신 전 실패·응답 거부)
    public static TossApiException rejected(String message, Throwable cause) {
        return new TossApiException(message, cause, Conflict.NONE, true);
    }
}
