package com.kista.domain.strategy;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.Strategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static java.math.RoundingMode.HALF_UP;

// PRIVACY 전략의 주문 계획 + 최소금액 정책
// 기존 TradingOrderPlanner.calcPrivacy + CycleRotationService.resolveMinRequired(PRIVACY) 이전
@Slf4j
@Component
@RequiredArgsConstructor
public class PrivacyCycleOrderStrategy implements CycleOrderStrategy {

    // 사이클 재등록 최소금액 — currentCycleStart ÷ 2 (배수 산정 하한)
    private static final BigDecimal MIN_DEPOSIT_DIVISOR = BigDecimal.valueOf(2);

    private final PrivacyTradingStrategy privacyStrategy;

    @Override
    public Strategy.Type cycleType() {
        return Strategy.Type.PRIVACY;
    }

    @Override
    public Optional<OrderPlan> plan(PlanContext ctx) {
        // 기준매매표 없으면 전략 차원 skip — 서비스는 OrderPlan absent로 skip 처리
        if (ctx.privacyBase() == null) {
            log.warn("[PRIVACY] 기준 매매표 미수신 — 매매 건너뜀: [{}]", ctx.label());
            return Optional.empty();
        }
        // initialUsdDeposit은 PlanContext에서 직접 수신 (StrategyCycle에서 출처)
        List<Order> orders = privacyStrategy.buildOrders(ctx.balance(), ctx.initialUsdDeposit(), ctx.privacyBase());
        return Optional.of(new OrderPlan(null, orders));
    }

    @Override
    public BigDecimal minRequiredDeposit(BigDecimal price, PrivacyTradeBase privacyBase) {
        if (privacyBase == null) return null;
        return privacyBase.currentCycleStart().divide(MIN_DEPOSIT_DIVISOR, 2, HALF_UP);
    }
}
