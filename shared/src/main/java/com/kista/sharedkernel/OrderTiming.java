package com.kista.sharedkernel;

// 주문 접수 시점 — admin·trading·matching이 공유하는 순수 값(outbound-zero)
public enum OrderTiming {
    AT_CLOSE,   // 마감 배치(04:30 KST)에 접수 — 기본값
    AT_OPEN,    // 개장 시점(22:30 KST)에 선접수
    IMMEDIATE   // 관리자 재주문 즉시 접수 (정규장 중에만 사용)
}
