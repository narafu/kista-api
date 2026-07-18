package com.kista.domain.model.order;

import com.kista.domain.model.strategy.Strategy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

// 바로주문 미리보기 시 계좌 내 BUY 예산 경쟁 시뮬레이션 결과 — TradingBuyCompetitionSimulator 산출
// 실제 야간 배치(TradingOrderBudgetAllocator)와 동일한 우선순위 정렬을 재현한 근사치
public record BuyCompetitionPreview(
        boolean sufficientBudget,                        // 대상 전략 BUY가 실제 배치에서 승인될지 근사 판정
        BigDecimal availableDeposit,                      // 라이브 예수금 - 타 전략 당일 PLANNED BUY 합계
        BigDecimal requiredForThisStrategy,               // 대상 전략 오늘자 BUY 합계
        BigDecimal consumedByHigherPriority,              // 대상 전략보다 우선순위 앞선 경쟁 전략 필요금액 합
        List<CompetingStrategy> blockedByHigherPriority,  // 우선순위 정렬 순서(높은 순) 유지
        List<UUID> uncertainStrategyIds                   // 계산 실패/skip돼 0으로 처리된 전략 id
) {
    // 경쟁 전략 1건 — priority는 CycleOrderStrategy.allocationPriority() 값(작을수록 먼저 승인)
    public record CompetingStrategy(
            UUID strategyId,
            Strategy.Type type,
            Strategy.Ticker ticker,
            BigDecimal requiredBuyUsd,
            int priority
    ) {}
}
