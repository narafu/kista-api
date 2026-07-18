package com.kista.application.service.trading;

import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.strategy.CycleOrderStrategies;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.UUID;
import java.util.function.Function;

// BUY 예산 배정 우선순위 정렬 규칙의 단일 소스
// TradingOrderBudgetAllocator(실제 야간 배치)와 TradingBuyCompetitionSimulator(미리보기 시뮬레이션)가
// 동일 규칙을 공유해야 두 결과가 어긋나지 않는다
// package-private — application/service/trading 패키지 전용
final class BuyPriorityOrdering {

    private BuyPriorityOrdering() {}

    // 타입 우선순위(작을수록 먼저) → 금액 오름차순 → strategyId → cycleId
    static <T> Comparator<T> comparator(CycleOrderStrategies cycleOrderStrategies,
                                         Function<T, Strategy.Type> typeFn,
                                         Function<T, BigDecimal> amountFn,
                                         Function<T, UUID> strategyIdFn,
                                         Function<T, UUID> cycleIdFn) {
        return Comparator
                .comparingInt((T t) -> cycleOrderStrategies.of(typeFn.apply(t)).allocationPriority())
                .thenComparing(amountFn)
                .thenComparing(strategyIdFn)
                .thenComparing(cycleIdFn);
    }
}
