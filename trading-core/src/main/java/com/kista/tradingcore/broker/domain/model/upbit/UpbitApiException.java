package com.kista.tradingcore.broker.domain.model.upbit;

import com.kista.tradingcore.broker.domain.model.BrokerApiException;

// 업비트 API 실패(자격증명·한도 외) — 503(BROKER_UNAVAILABLE)으로 매핑된다. 업비트 원문 message는 싣지 않는다(로그 전용)
public class UpbitApiException extends BrokerApiException {

    private final int status;       // HTTP 상태 (I/O 실패면 0)
    private final String errorName; // 업비트 error.name (없으면 null) — 어댑터가 order_not_found 등 분기에 사용

    public UpbitApiException(int status, String errorName, String message, Throwable cause, boolean rejected) {
        super("Upbit", message, cause, Conflict.NONE, rejected);
        this.status = status;
        this.errorName = errorName;
    }

    public int status() {
        return status;
    }

    public String errorName() {
        return errorName;
    }
}
