package com.kista.tradingcore.broker.domain.model.toss;

import com.kista.tradingcore.broker.domain.model.BrokerApiException;

// Toss 외부 API 호출 실패 — BrokerApiException으로 503 매핑됨
public class TossApiException extends BrokerApiException {

    private final String errorCode; // Toss 응답 error.code (없으면 null) — 어댑터가 already-filled 등 분기에 사용

    public TossApiException(String message, Throwable cause) {
        super("Toss", message, cause);
        this.errorCode = null;
    }

    public TossApiException(String message, Throwable cause, boolean rejected) {
        super("Toss", message, cause, rejected);
        this.errorCode = null;
    }

    public TossApiException(String message, Throwable cause, boolean rejected, boolean insufficientFunds, String errorCode) {
        super("Toss", message, cause, rejected, insufficientFunds);
        this.errorCode = errorCode;
    }

    // 증권사가 접수하지 않았음이 확실한 실패(송신 전 실패·응답 거부)
    public static TossApiException rejected(String message, Throwable cause) {
        return new TossApiException(message, cause, true);
    }

    public String errorCode() {
        return errorCode;
    }
}
