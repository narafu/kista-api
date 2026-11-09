package com.kista.tradingcore.matching.domain.strategy;

import com.kista.shared.kernel.Currency;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderDirection;
import com.kista.tradingcore.matching.domain.model.PrivacyPlan;
import com.kista.tradingcore.matching.domain.model.InfinitePosition;
import com.kista.tradingcore.matching.domain.model.ReverseModePosition;
import com.kista.tradingcore.matching.domain.model.VrPosition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.math.RoundingMode.HALF_UP;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.StrategyDefaults;
import com.kista.shared.kernel.StrategyTicker;


// INFINITE 전략의 주문 계획 + 최소금액 정책
// 기존 TradingOrderPlanner.calcInfinite + CycleRotationService.resolveMinRequired(INFINITE) 이전
@Slf4j
@RequiredArgsConstructor
public class InfiniteCycleOrderStrategy implements CycleOrderStrategy {

    // 사이클 재등록 최소금액 계수 — 현재가 × (divisionCount × 2)
    // 평단가 매수 + LOC 매수 각 최소 1주 보장
    private static final double MIN_DEPOSIT_FACTOR = 2.0;

    private final InfiniteStrategy infiniteStrategy;
    private final ReverseInfiniteStrategy reverseStrategy; // 리버스모드 전략

    @Override
    public StrategyType cycleType() { return StrategyType.INFINITE; }

    @Override
    public boolean requiresPrevClose() { return true; }

    @Override
    public boolean tracksReverseMode() { return true; }

    @Override
    public boolean canSkipOrderComputation(List<PlannedOrder> existingOrders, Set<OrderTiming> creatableTimings) {
        Set<ExistingLegSlot> concreteSlots = existingOrders.stream()
                .filter(order -> creatableTimings.contains(order.timing()))
                .map(ExistingLegSlot::of)
                .collect(Collectors.toSet());
        if (concreteSlots.isEmpty()) return false;

        boolean hasAtOpenTiming = creatableTimings.contains(OrderTiming.AT_OPEN);
        boolean hasAtCloseTiming = creatableTimings.contains(OrderTiming.AT_CLOSE);
        boolean atOpenComplete = !hasAtOpenTiming;

        Set<String> atCloseLegs = existingOrders.stream()
                .filter(order -> hasAtCloseTiming && order.timing() == OrderTiming.AT_CLOSE)
                .filter(order -> order.direction() == OrderDirection.BUY)
                .map(PlannedOrder::orderLeg)
                .collect(Collectors.toSet());

        boolean earlyComplete = atCloseLegs.contains(InfiniteStrategy.EARLY_AVG_LEG)
                && atCloseLegs.contains(InfiniteStrategy.EARLY_REF_LEG);
        boolean earlyMergedComplete = atCloseLegs.contains(InfiniteStrategy.EARLY_MERGED_LEG);
        boolean lateComplete = atCloseLegs.contains("INFINITE_LATE_REF_BUY");
        boolean correctionComplete = atCloseLegs.contains("INFINITE_CORRECTION_01")
                && atCloseLegs.contains("INFINITE_CORRECTION_02")
                && atCloseLegs.contains("INFINITE_CORRECTION_03")
                && (earlyComplete || earlyMergedComplete || lateComplete);
        boolean atCloseComplete = !hasAtCloseTiming
                || correctionComplete
                || (hasSlot(concreteSlots, OrderTiming.AT_CLOSE, OrderDirection.BUY, ReverseInfiniteStrategy.LOC_BUY_LEG)
                        && hasSlot(concreteSlots, OrderTiming.AT_CLOSE, OrderDirection.SELL, ReverseInfiniteStrategy.LOC_SELL_LEG));

        return atOpenComplete && atCloseComplete;
    }

    private boolean hasSlot(Set<ExistingLegSlot> slots, OrderTiming timing,
                            OrderDirection direction, String orderLeg) {
        return slots.contains(new ExistingLegSlot(timing, direction, orderLeg));
    }

    private record ExistingLegSlot(
            OrderTiming timing,
            OrderDirection direction,
            String orderLeg
    ) {
        static ExistingLegSlot of(PlannedOrder order) {
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
        if (ctx.balance().wholeShares() == 0 && inputs.prevClosePrice() == null) {
            throw new IllegalStateException("%s의 전일 종가를 조회하지 못했습니다.".formatted(ctx.ticker().name()));
        }
        int divisionCount = inputs.divisionCount() != null ? inputs.divisionCount() : StrategyDefaults.DEFAULT_DIVISION_COUNT;
        InfinitePosition position = new InfinitePosition(ctx.balance(), ctx.ticker(), inputs.prevClosePrice(), divisionCount);
        List<PlannedOrder> orders = infiniteStrategy.buildOrders(position, ctx.tradeDate());
        log.info("[accountId={}] 전략 계산(일반모드): priceOffsetRate={}, currentRound={}, unitAmount={}, orders={}",
                ctx.accountId(), position.priceOffsetRate(), position.currentRound(),
                position.unitAmount(), orders.size());
        return Optional.of(new OrderPlan(position, null, orders));
    }

    // 리버스모드 — 별지점 기준 매도/쿼터매수
    // isFirstReverseDay=true이면 첫날 MOC 즉시 청산, false이면 LOC 분할 매도 + 쿼터매수
    private Optional<OrderPlan> planReverseMode(PlanContext ctx) {
        PlanContext.InfiniteInputs inputs = ctx.infinite();
        ReverseModePosition position = ReverseModePosition.of(
                ctx.balance(),
                ctx.ticker(),
                inputs.divisionCount() != null ? inputs.divisionCount() : StrategyDefaults.DEFAULT_DIVISION_COUNT,
                inputs.starPointPrice(),
                inputs.isFirstReverseDay()
        );

        List<PlannedOrder> orders = position.isFirstDay()
                ? reverseStrategy.buildFirstDayOrders(position, ctx.tradeDate())
                : reverseStrategy.buildOrders(position, ctx.tradeDate());

        log.info("[accountId={}] 전략 계산(리버스모드): isFirstDay={}, holdings={}, starPointPrice={}, orders={}",
                ctx.accountId(), position.isFirstDay(), position.holdings(), position.starPointPrice(), orders.size());
        // 리버스모드 position은 가격 캡 재산정(쿼터 예산 = 원장 예수금/4) 전용 — reverseMode=true라 미리보기 화면에는 내보내지 않는다
        InfinitePosition capPosition = new InfinitePosition(ctx.balance(), ctx.ticker(), inputs.prevClosePrice(), position.divisionCount());
        return Optional.of(new OrderPlan(capPosition, null, orders, List.of(), Map.of(), true));
    }

    @Override
    public BigDecimal minRequiredDeposit(BigDecimal price, PrivacyPlan privacyPlan, int divisionCount) {
        if (price == null) return null;
        BigDecimal multiplier = BigDecimal.valueOf(divisionCount * MIN_DEPOSIT_FACTOR);
        return price.multiply(multiplier).setScale(Currency.USD.amountScale(), HALF_UP);
    }

    @Override
    public List<PlannedOrder> capBuyOrders(List<PlannedOrder> buyOrders, BigDecimal cap,
                                            InfinitePosition position, VrPosition vrPosition,
                                            StrategyTicker ticker, LocalDate tradeDate) {
        if (position == null) return buyOrders;
        // 리버스 쿼터매수 — 가격만 캡, 수량은 쿼터 예산 ÷ 캡 가격으로 재산정(일반모드 캡과 같은 예산 보존 방식)
        // 일반모드 사다리 재구성(buildCappedBuyOrders)으로 넘기면 리버스 BUY가 일반모드 주문으로 바뀌므로 먼저 분기한다
        if (buyOrders.stream().anyMatch(o -> ReverseInfiniteStrategy.LOC_BUY_LEG.equals(o.orderLeg()))) {
            return buyOrders.stream().map(o -> capReverseQuotaBuy(o, cap, position.deposit())).toList();
        }
        return infiniteStrategy.buildCappedBuyOrders(position, tradeDate, buyOrders, cap);
    }

    private static PlannedOrder capReverseQuotaBuy(PlannedOrder order, BigDecimal cap, BigDecimal deposit) {
        if (order.price().compareTo(cap) <= 0) return order;
        // 같은 원장이면 캡 가격이 원래 매수가보다 낮아 수량은 원래 이상이 된다 — 쿼터 예산 상한은 그대로
        return order.withPrice(cap).withQuantity(ReverseModePosition.quotaBuyQuantity(deposit, cap));
    }

    @Override
    public List<PlannedOrder> fitBuysToBudget(List<PlannedOrder> buyOrders, BigDecimal budget) {
        return InfiniteStrategy.trimCorrectionsToBudget(buyOrders, budget);
    }

    @Override
    public boolean needsCapCheck(InfinitePosition position, VrPosition vrPosition) {
        return position != null;
    }
}
