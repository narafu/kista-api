package com.kista.sharedkernel;

// 주문 상태 — admin·trading 양쪽이 공유하는 순수 값(outbound-zero), OrderDirection/OrderType과 동일 패턴으로 승격
public enum OrderStatus {
    PLANNED,           // DB 저장, 증권사 접수 대기
    PLACED,            // 증권사 접수 완료
    FILLED,            // 전량 체결
    PARTIALLY_FILLED,  // 부분 체결 (filledQuantity < quantity)
    FAILED,            // 증권사 접수 실패
    CANCELLED          // 사용자 취소 또는 미체결로 취소 처리 완료
}
