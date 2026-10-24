package com.kista.tradingcore.broker.domain.model.kis;

import com.kista.tradingcore.broker.domain.model.BrokerApiException;

// KIS 외부 API 호출 실패를 나타내는 도메인 예외 — BrokerApiException으로 503 매핑됨
public class KisApiException extends BrokerApiException {
    public KisApiException(String message, Throwable cause) {
        super("KIS", message, cause, Conflict.NONE);
    }

    private KisApiException(String message, Throwable cause, boolean rejected, boolean insufficientFunds) {
        super("KIS", message, cause, Conflict.NONE, rejected, insufficientFunds);
    }

    // 증권사가 접수하지 않았음이 확실한 실패(송신 전 실패·응답 거부)
    public static KisApiException rejected(String message, Throwable cause) {
        return new KisApiException(message, cause, true, false);
    }

    // 확정 거절 중 잔고·가능수량 부족(APBK0988)
    public static KisApiException rejectedInsufficientFunds(String message, Throwable cause) {
        return new KisApiException(message, cause, true, true);
    }
}
