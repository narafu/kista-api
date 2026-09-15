package com.kista.sharedkernel;

// 주문 유형 — broker/privacy/matching이 공유하는 전역 어휘(2026-09-09 own-type 원장 재판정으로 승격)
public enum OrderType {
    LOC,   // Limit On Close: 종가 지정가 주문
    MOC,   // Market On Close: 종가 시장가 주문
    LIMIT  // 일반 지정가 주문
}
