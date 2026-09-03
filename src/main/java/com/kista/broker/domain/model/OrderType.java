package com.kista.broker.domain.model;

// 주문 유형 — trading.Order.OrderType과 별개(모듈 경계상 공유 불가), 값 집합만 동일
public enum OrderType {
    LOC,   // Limit On Close: 종가 지정가 주문
    MOC,   // Market On Close: 종가 시장가 주문
    LIMIT  // 일반 지정가 주문
}
