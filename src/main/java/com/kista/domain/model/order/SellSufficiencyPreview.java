package com.kista.domain.model.order;

// 바로주문 미리보기 시 SELL 판매가능수량 충족 여부 근사 판정 — TradingSellSufficiencySimulator 산출
// BUY 예산 경쟁과 달리 계좌당 종목 유일성 제약상 동일 계좌 내 타 전략과 경쟁이 발생하지 않아
// 우선순위 정렬 없이 단일 종목 기준 판정만 수행한다 (TradingOrderBudgetAllocator의 근사치)
public record SellSufficiencyPreview(
        boolean sufficientQuantity,     // 대상 전략 SELL이 실제 배치에서 승인될지 근사 판정 (liveQuantityUnavailable=true면 신뢰 불가)
        int sellableQuantity,           // 브로커 판매가능수량 (liveQuantityUnavailable=true면 0)
        int reservedQuantity,           // 동일 계좌·종목·거래일 기준 기존 PLANNED/PLACED SELL 예약 수량 합
        int requiredQuantity,           // 대상 전략 오늘자 SELL 합계 수량
        boolean liveQuantityUnavailable // true면 브로커 판매가능수량 조회 자체가 실패해 판정을 생략함
) {
    // 브로커 판매가능수량 조회 실패(토큰 재시도 소진 등) 시 사용 — 주문 계획은 정상 반환하되 충족 판정만 생략
    public static SellSufficiencyPreview unavailable(int requiredQuantity) {
        return new SellSufficiencyPreview(true, 0, 0, requiredQuantity, true);
    }
}
