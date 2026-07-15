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
import java.util.Set;
import java.util.stream.Collectors;

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

    private final InfiniteStrategy infiniteStrategy;
    private final ReverseInfiniteStrategy reverseStrategy; // 리버스모드 전략

    @Override
    public Strategy.Type cycleType() { return Strategy.Type.INFINITE; }

    @Override
    public boolean requiresPrevClose() { return true; }

    @Override
    public boolean supportsReverseMode() { return true; }

    @Override
    public boolean tracksReverseMode() { return true; }

    @Override
    public List<Integer> availableDivisionCounts() { return List.of(20); }

    @Override
    public PriceCapMode priceCapMode() { return PriceCapMode.INFINITE_POSITION; }

    @Override
    public int allocationPriority() { return 1; }

    @Override
    public boolean canSkipOrderComputation(List<Order> existingOrders, Set<Order.OrderTiming> creatableTimings) {
        List<Order> targetOrders = existingOrders.stream()
                .filter(order -> creatableTimings.contains(order.timing()))
                .toList();
        // 리버스모드 여부는 계산 전에는 알 수 없으므로 legacy 주문은 양 방향이 모두 있어야만 전체 슬롯을 점유한다.
        if (!targetOrders.isEmpty()
                && targetOrders.stream().allMatch(order -> Order.UNKNOWN_LEG.equals(order.orderLeg()))
                && creatableTimings.stream().allMatch(timing -> targetOrders.stream()
                        .anyMatch(order -> order.timing() == timing && order.direction() == Order.OrderDirection.BUY)
                        && targetOrders.stream().anyMatch(order -> order.timing() == timing
                                && order.direction() == Order.OrderDirection.SELL))) {
            return true;
        }

        Set<ExistingLegSlot> concreteSlots = existingOrders.stream()
                .filter(order -> creatableTimings.contains(order.timing()))
                .filter(order -> !Order.UNKNOWN_LEG.equals(order.orderLeg()))
                .map(ExistingLegSlot::of)
                .collect(Collectors.toSet());
        if (concreteSlots.isEmpty()) return false;

        boolean hasAtOpenTiming = creatableTimings.contains(Order.OrderTiming.AT_OPEN);
        boolean hasAtCloseTiming = creatableTimings.contains(Order.OrderTiming.AT_CLOSE);
        boolean atOpenComplete = !hasAtOpenTiming;

        Set<String> atCloseLegs = existingOrders.stream()
                .filter(order -> hasAtCloseTiming && order.timing() == Order.OrderTiming.AT_CLOSE)
                .filter(order -> !Order.UNKNOWN_LEG.equals(order.orderLeg()))
                .filter(order -> order.direction() == Order.OrderDirection.BUY)
                .map(Order::orderLeg)
                .collect(Collectors.toSet());

        boolean earlyComplete = atCloseLegs.contains("INFINITE_EARLY_AVG_BUY")
                && atCloseLegs.contains("INFINITE_EARLY_REF_BUY");
        boolean earlyMergedComplete = atCloseLegs.contains("INFINITE_EARLY_MERGED_BUY");
        boolean lateComplete = atCloseLegs.contains("INFINITE_LATE_REF_BUY");
        boolean correctionComplete = atCloseLegs.contains("INFINITE_CORRECTION_01")
                && atCloseLegs.contains("INFINITE_CORRECTION_02")
                && atCloseLegs.contains("INFINITE_CORRECTION_03")
                && (earlyComplete || earlyMergedComplete || lateComplete);
        boolean atCloseComplete = !hasAtCloseTiming
                || correctionComplete
                || (hasSlot(concreteSlots, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, "REVERSE_INFINITE_LOC_BUY")
                        && hasSlot(concreteSlots, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.SELL, "REVERSE_INFINITE_LOC_SELL"));

        return atOpenComplete && atCloseComplete;
    }

    private boolean hasSlot(Set<ExistingLegSlot> slots, Order.OrderTiming timing,
                            Order.OrderDirection direction, String orderLeg) {
        return slots.contains(new ExistingLegSlot(timing, direction, orderLeg));
    }

    private record ExistingLegSlot(
            Order.OrderTiming timing,
            Order.OrderDirection direction,
            String orderLeg
    ) {
        static ExistingLegSlot of(Order order) {
            return new ExistingLegSlot(order.timing(), order.direction(), order.orderLeg());
        }
    }

    @Override
    public Optional<OrderPlan> plan(PlanContext ctx) {
        // 리버스모드 분기 — cycle_position 최신 행의 isReverseMode가 true이면 리버스모드 전략 사용
        if (ctx.infinite().isReverseMode()) {
            return planReverseMode(ctx);
        }
        return planNormalMode(ctx);
    }

    // 일반 모드 — InfiniteStrategy 사용
    private Optional<OrderPlan> planNormalMode(PlanContext ctx) {
        PlanContext.InfiniteInputs inputs = ctx.infinite();
        // 0회차(holdings==0)에서 전일종가 없으면 InfinitePosition 생성 자체가 불가
        if (ctx.balance().holdings() == 0 && inputs.prevClosePrice() == null) {
            throw new IllegalStateException("전일종가 조회 실패: " + ctx.strategy().ticker().name());
        }
        int divisionCount = inputs.divisionCount() != null ? inputs.divisionCount() : Strategy.DEFAULT_DIVISION_COUNT;
        InfinitePosition position = new InfinitePosition(ctx.balance(), ctx.strategy().ticker(), inputs.prevClosePrice(), divisionCount);
        List<Order> orders = infiniteStrategy.buildOrders(position, ctx.tradeDate());
        log.info("[{}] 전략 계산(일반모드): priceOffsetRate={}, currentRound={}, unitAmount={}, orders={}",
                ctx.label(), position.priceOffsetRate(), position.currentRound(),
                position.unitAmount(), orders.size());
        return Optional.of(new OrderPlan(position, orders));
    }

    // 리버스모드 — 별지점 기준 매도/쿼터매수
    // isFirstReverseDay=true이면 첫날 MOC 즉시 청산, false이면 LOC 분할 매도 + 쿼터매수
    private Optional<OrderPlan> planReverseMode(PlanContext ctx) {
        PlanContext.InfiniteInputs inputs = ctx.infinite();
        ReverseModePosition position = ReverseModePosition.of(
                ctx.balance(),
                ctx.strategy().ticker(),
                inputs.divisionCount() != null ? inputs.divisionCount() : Strategy.DEFAULT_DIVISION_COUNT,
                inputs.starPointPrice(),
                inputs.isFirstReverseDay()
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
