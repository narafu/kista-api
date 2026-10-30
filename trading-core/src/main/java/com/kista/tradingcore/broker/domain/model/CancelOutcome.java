package com.kista.tradingcore.broker.domain.model;

// 주문 취소 요청의 결과 — 이미 종결된 주문은 예외가 아니라 반환값으로 알린다
public enum CancelOutcome {
    CANCELLED,        // 취소 접수
    ALREADY_FILLED,   // 이미 전량 체결
    ALREADY_CANCELED, // 이미 취소·만료(부분체결 후 만료 포함)
}
