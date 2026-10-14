package com.kista.api.market.domain.model;

// trading-core 캔들 내부 API가 503(증권사 API 장애)을 돌려준 경우 — GlobalExceptionHandler가 503 BROKER_UNAVAILABLE로 매핑한다.
// 원 장애는 trading-core가 이미 app_error_logs에 보고하므로 root는 매핑만 하고 중복 보고하지 않는다
public class CandleUnavailableException extends RuntimeException {
    public CandleUnavailableException(String message) {
        super(message);
    }
}
