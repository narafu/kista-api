package com.kista.domain.model.order;

// 주문 취소 불가 상태 — 409 Conflict (PLACED 아닌 주문 취소 시도)
public class OrderCancelException extends RuntimeException {
    public OrderCancelException(String message) {
        super(message);
    }
}
