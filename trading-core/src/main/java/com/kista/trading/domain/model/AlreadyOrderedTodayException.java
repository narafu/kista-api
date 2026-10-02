package com.kista.trading.domain.model;

// 바로주문 이중 실행 거부 — 오늘 이미 PLANNED/PLACED 주문이 있는 전략. 409 + ALREADY_ORDERED_TODAY
public class AlreadyOrderedTodayException extends ManualTradingException {
    public AlreadyOrderedTodayException() {
        super("오늘 이미 주문이 등록된 전략입니다.");
    }
}
