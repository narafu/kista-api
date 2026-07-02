package com.kista.domain.strategy;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.strategy.ReverseModePosition;
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

    // 사이클 재등록 최소금액 계수 — 현재가 × (divisionCount × 2)
    // 평단가 매수 + LOC 매수 각 최소 1주 보장
    private static final double MIN_DEPOSIT_FACTOR = 2.0;

    private final InfiniteTradingStrategy infiniteStrategy;
    private final ReverseInfiniteTradingStrategy reverseStrategy; // 리버스모드 전략 (구현체: ReverseInfiniteStrategy)

    @Override
    public Strategy.Type cycleType() { return Strategy.Type.INFINITE; }

    @Override
    public boolean requiresPrevClose() { return true; }

    @Override
    public boolean supportsReverseMode() { return true; }

    @Override
    public List<Integer> availableDivisionCounts() { return List.of(20); }

    @Override
    public Optional<OrderPlan> plan(PlanContext ctx) {
        // 리버스모드 분기 — cycle_position 최신 행의 isReverseMode가 true이면 리버스모드 전략 사용
        if (ctx.isReverseMode()) {
            return planReverseMode(ctx);
        }
        return planNormalMode(ctx);
    }

    // 일반 모드 — 기존 InfiniteTradingStrategy 사용
    private Optional<OrderPlan> planNormalMode(PlanContext ctx) {
        // 0회차(holdings==0)에서 전일종가 없으면 InfinitePosition 생성 자체가 불가
        if (ctx.balance().holdings() == 0 && ctx.prevClosePrice() == null) {
            throw new IllegalStateException("전일종가 조회 실패: " + ctx.strategy().ticker().name());
        }
        int divisionCount = ctx.divisionCount() != null ? ctx.divisionCount() : Strategy.DEFAULT_DIVISION_COUNT;
        InfinitePosition position = new InfinitePosition(ctx.balance(), ctx.strategy().ticker(), ctx.prevClosePrice(), divisionCount);
        List<Order> orders = infiniteStrategy.buildOrders(position, ctx.tradeDate());
        log.info("[{}] 전략 계산(일반모드): priceOffsetRate={}, currentRound={}, unitAmount={}, orders={}",
                ctx.label(), position.priceOffsetRate(), position.currentRound(),
                position.unitAmount(), orders.size());
        return Optional.of(new OrderPlan(position, orders));
    }

    // 리버스모드 — 별지점 기준 매도/쿼터매수
    // isFirstReverseDay=true이면 첫날 MOC 즉시 청산, false이면 LOC 분할 매도 + 쿼터매수
    private Optional<OrderPlan> planReverseMode(PlanContext ctx) {
        ReverseModePosition position = ReverseModePosition.of(
                ctx.balance(),
                ctx.strategy().ticker(),
                ctx.divisionCount() != null ? ctx.divisionCount() : Strategy.DEFAULT_DIVISION_COUNT,
                ctx.starPointPrice(),
                ctx.isFirstReverseDay()
        );

        List<Order> orders = position.isFirstDay()
                ? reverseStrategy.buildFirstDayOrders(position, ctx.tradeDate())
                : reverseStrategy.buildOrders(position, ctx.tradeDate());

        log.info("[{}] 전략 계산(리버스모드): isFirstDay={}, holdings={}, starPointPrice={}, orders={}",
                ctx.label(), position.isFirstDay(), position.holdings(), position.starPointPrice(), orders.size());
        // 리버스모드에서 InfinitePosition은 null (OrderPlan.position()은 일반모드 전용)
        return Optional.of(new OrderPlan(null, orders));
    }

    @Override
    public BigDecimal minRequiredDeposit(BigDecimal price, PrivacyTradeBase privacyBase, int divisionCount) {
        if (price == null) return null;
        BigDecimal multiplier = BigDecimal.valueOf(divisionCount * MIN_DEPOSIT_FACTOR);
        return price.multiply(multiplier).setScale(2, HALF_UP);
    }
}
