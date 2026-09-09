package com.kista.matching.domain.model;

// 주문 접수 시점
public enum OrderTiming {
    AT_CLOSE,   // 마감 배치(04:30 KST)에 접수 — 기본값
    AT_OPEN,    // 개장 시점(22:30 KST)에 선접수
    IMMEDIATE   // 관리자 재주문 즉시 접수 (정규장 중에만 사용)
}
