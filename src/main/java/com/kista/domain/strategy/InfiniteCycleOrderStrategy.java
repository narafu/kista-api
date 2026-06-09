package com.kista.domain.strategy;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.strategy.Strategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static java.math.RoundingMode.HALF_UP;

// INFINITE 전략의 주문 계획 + 최소금액 정책
// 기존 TradingOrderPlanner.calcInfinite + CycleRotationService.resolveMinRequired(INFINITE) 이전
@Slf4j
@Component
@RequiredArgsConstructor
public class InfiniteCycleOrderStrategy implements CycleOrderStrategy {

    // 사이클 재등록 최소금액 안전 계수 — 현재가 × 44 (= 20round × 2 × 1.1 safety)
    private static final BigDecimal MIN_DEPOSIT_MULTIPLIER = BigDecimal.valueOf(44);

    private final InfiniteTradingStrategy infiniteStrategy;

    @Override
    public Strategy.Type cycleType() {
        return Strategy.Type.INFINITE;
    }

    @Override
    public Optional<OrderPlan> plan(PlanContext ctx) {
        // 0회차(holdings==0)에서 전일종가 없으면 InfinitePosition 생성 자체가 불가
        if (ctx.balance().holdings() == 0 && ctx.prevClosePrice() == null) {
            throw new IllegalStateException("전일종가 조회 실패: " + ctx.strategy().ticker().name());
        }
        InfinitePosition position = new InfinitePosition(ctx.balance(), ctx.strategy().ticker(), ctx.prevClosePrice());
        List<Order> orders = infiniteStrategy.buildOrders(position, ctx.tradeDate());
        log.info("[{}] 전략 계산: priceOffsetRate={}, currentRound={}, unitAmount={}, orders={}",
                ctx.label(), position.priceOffsetRate(), position.currentRound(),
                position.unitAmount(), orders.size());
        return Optional.of(new OrderPlan(position, orders));
    }

    @Override
    public BigDecimal minRequiredDeposit(BigDecimal price, PrivacyTradeBase privacyBase) {
        if (price == null) return null;
        return price.multiply(MIN_DEPOSIT_MULTIPLIER).setScale(2, HALF_UP);
    }
}
