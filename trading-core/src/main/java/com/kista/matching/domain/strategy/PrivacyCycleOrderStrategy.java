package com.kista.matching.domain.strategy;

import com.kista.matching.domain.model.PlannedOrder;
import com.kista.privacy.domain.model.PrivacyTradeBase;
import com.kista.matching.domain.model.InfinitePosition;
import com.kista.matching.domain.model.VrPosition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static java.math.RoundingMode.HALF_UP;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;

// PRIVACY 전략의 주문 계획 + 최소금액 정책
// 기존 TradingOrderPlanner.calcPrivacy + CycleRotationService.resolveMinRequired(PRIVACY) 이전
@Slf4j
@RequiredArgsConstructor
public class PrivacyCycleOrderStrategy implements CycleOrderStrategy {

    private final PrivacyStrategy privacyStrategy;

    @Override
    public StrategyType cycleType() { return StrategyType.PRIVACY; }

    @Override
    public int allocationPriority() { return 2; }

    @Override
    public boolean requiresPrivacyBase() { return true; }

    @Override
    public Optional<OrderPlan> plan(PlanContext ctx) {
        PlanContext.PrivacyInputs inputs = ctx.privacy();
        // 기준매매표 없으면 전략 차원 skip — 서비스는 OrderPlan absent로 skip 처리
        if (inputs.privacyBase() == null) {
            log.warn("[PRIVACY] 기준 매매표 미수신 — 매매 건너뜀: [{}]", ctx.label());
            return Optional.empty();
        }
        // initialUsdDeposit은 PlanContext에서 직접 수신 (StrategyCycle에서 출처)
        List<PlannedOrder> orders = privacyStrategy.buildOrders(ctx.balance(), inputs.initialUsdDeposit(), inputs.privacyBase());
        return Optional.of(new OrderPlan(null, null, orders));
    }

    @Override
    public BigDecimal minRequiredDeposit(BigDecimal price, PrivacyTradeBase privacyBase, int divisionCount) {
        if (privacyBase == null) return null;
        // currentCycleStart 0.5배 적용
        return privacyBase.currentCycleStart().divide(BigDecimal.valueOf(2), 2, HALF_UP);
    }

    @Override
    public List<PlannedOrder> capBuyOrders(List<PlannedOrder> buyOrders, BigDecimal cap,
                                            InfinitePosition position, VrPosition vrPosition,
                                            StrategyTicker ticker, LocalDate tradeDate) {
        return buyOrders.stream()
                .map(o -> o.price().compareTo(cap) > 0 ? o.withPrice(cap) : o)
                .toList();
    }

    @Override
    public boolean capsIndividualOrders() { return true; }
}
