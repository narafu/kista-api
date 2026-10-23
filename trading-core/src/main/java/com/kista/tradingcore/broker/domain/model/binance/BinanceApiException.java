package com.kista.tradingcore.broker.domain.model.binance;

import com.kista.tradingcore.broker.domain.model.BrokerApiException;

// 바이낸스 API 실패(자격증명·한도 외) — 503(BROKER_UNAVAILABLE)으로 매핑된다. 바이낸스 원문 msg는 싣지 않는다(로그 전용)
public class BinanceApiException extends BrokerApiException {

    private final int status;   // HTTP 상태 (I/O 실패면 0)
    private final Integer code; // 바이낸스 오류 코드 (없으면 null) — 어댑터가 -2013 등 분기에 사용

    public BinanceApiException(int status, Integer code, String message, Throwable cause, boolean rejected) {
        super("Binance", message, cause, Conflict.NONE, rejected);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public Integer code() {
        return code;
    }
}
