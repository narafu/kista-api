package com.kista.tradingcore.trading.domain.model;

// 주문 상태 전이 경합 — 읽은 뒤 다른 경로(배치 접수·사용자 취소·일시중지 정리)가 상태를 바꿨거나 행을 지웠다
public class OrderStateConflictException extends IllegalStateException {
    public OrderStateConflictException(String message) {
        super(message);
    }
}
