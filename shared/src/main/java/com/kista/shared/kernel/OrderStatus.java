package com.kista.shared.kernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 주문 상태 — admin·trading 양쪽이 공유하는 순수 값(outbound-zero), OrderDirection/OrderType과 동일 패턴으로 승격
@Getter
@RequiredArgsConstructor
public enum OrderStatus {
    PLANNED("예정"),               // DB 저장, 증권사 접수 대기
    SUBMITTING("접수 중"),         // 증권사 접수 진행 중(결과 미확정) — 재접수·재캡 대상 아님
    PLACED("접수"),                // 증권사 접수 완료
    FILLED("체결"),                // 전량 체결
    PARTIALLY_FILLED("부분체결"),  // 부분 체결 (filledQuantity < quantity)
    FAILED("실패"),                // 증권사 접수 실패
    CANCELLED("취소");             // 사용자 취소 또는 미체결로 취소 처리 완료

    private final String label; // 한국어 표시 이름 (메타 orderStatuses)
}
