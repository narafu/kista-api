package com.kista.domain.model.order;

// 수동 실행 시 주문 불가 상태 — 409 Conflict (주문 불가 시간대 / 오늘 이미 주문 존재)
public class ManualTradingException extends RuntimeException {
    public ManualTradingException(String message) {
        super(message);
    }

    public ManualTradingException(String message, Throwable cause) {
        super(message, cause);
    }
}
