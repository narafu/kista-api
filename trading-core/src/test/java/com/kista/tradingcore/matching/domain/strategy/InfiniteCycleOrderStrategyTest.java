package com.kista.tradingcore.matching.domain.strategy;

import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InfiniteCycleOrderStrategy.canSkipOrderComputation 검증")
class InfiniteCycleOrderStrategyTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 1, 15);
    private static final Set<OrderTiming> AT_CLOSE = Set.of(OrderTiming.AT_CLOSE);
    private static final Set<OrderTiming> AT_OPEN = Set.of(OrderTiming.AT_OPEN);

    private final InfiniteCycleOrderStrategy strategy = new InfiniteCycleOrderStrategy(null, null);

    // 지정 timing·방향·leg의 계획 주문 생성
    private static PlannedOrder order(OrderTiming timing, OrderDirection direction, String leg) {
        return PlannedOrder.of(TODAY, StrategyTicker.SOXL, OrderType.LOC, direction, 1,
                new BigDecimal("20"), timing, leg);
    }

    private static PlannedOrder closeBuy(String leg) {
        return order(OrderTiming.AT_CLOSE, OrderDirection.BUY, leg);
    }

    private static List<PlannedOrder> corrections() {
        return List.of(closeBuy("INFINITE_CORRECTION_01"), closeBuy("INFINITE_CORRECTION_02"),
                closeBuy("INFINITE_CORRECTION_03"));
    }

    private static List<PlannedOrder> with(List<PlannedOrder> base, PlannedOrder... extra) {
        var all = new ArrayList<>(base);
        all.addAll(List.of(extra));
        return all;
    }

    @Test
    @DisplayName("AT_CLOSE 전반 매수①② + 보정 3건이면 true")
    void atClose_earlyAvgRef_withCorrections_true() {
        var orders = with(corrections(), closeBuy(InfiniteStrategy.EARLY_AVG_LEG), closeBuy(InfiniteStrategy.EARLY_REF_LEG));
        assertThat(strategy.canSkipOrderComputation(orders, AT_CLOSE)).isTrue();
    }

    @Test
    @DisplayName("AT_CLOSE 병합 leg + 보정 3건이면 true")
    void atClose_earlyMerged_withCorrections_true() {
        var orders = with(corrections(), closeBuy(InfiniteStrategy.EARLY_MERGED_LEG));
        assertThat(strategy.canSkipOrderComputation(orders, AT_CLOSE)).isTrue();
    }

    @Test
    @DisplayName("AT_CLOSE 후반 LATE_REF_BUY + 보정 3건이면 true")
    void atClose_late_withCorrections_true() {
        var orders = with(corrections(), closeBuy("INFINITE_LATE_REF_BUY"));
        assertThat(strategy.canSkipOrderComputation(orders, AT_CLOSE)).isTrue();
    }

    @Test
    @DisplayName("AT_CLOSE 매수 leg만 있고 보정이 하나 빠지면 false")
    void atClose_missingCorrection_false() {
        var orders = List.of(closeBuy(InfiniteStrategy.EARLY_AVG_LEG), closeBuy(InfiniteStrategy.EARLY_REF_LEG),
                closeBuy("INFINITE_CORRECTION_01"), closeBuy("INFINITE_CORRECTION_02"));
        assertThat(strategy.canSkipOrderComputation(orders, AT_CLOSE)).isFalse();
    }

    @Test
    @DisplayName("AT_CLOSE 보정 3건만 있고 base 매수 leg가 없으면 false")
    void atClose_correctionsOnly_false() {
        assertThat(strategy.canSkipOrderComputation(corrections(), AT_CLOSE)).isFalse();
    }

    @Test
    @DisplayName("AT_CLOSE 전반 매수①만 있고 ②가 없으면 false")
    void atClose_onlyEarlyAvg_false() {
        var orders = with(corrections(), closeBuy(InfiniteStrategy.EARLY_AVG_LEG));
        assertThat(strategy.canSkipOrderComputation(orders, AT_CLOSE)).isFalse();
    }

    @Test
    @DisplayName("AT_CLOSE 리버스 LOC_BUY + LOC_SELL 둘 다 있으면 true")
    void atClose_reverseBoth_true() {
        var orders = List.of(closeBuy(ReverseInfiniteStrategy.LOC_BUY_LEG),
                order(OrderTiming.AT_CLOSE, OrderDirection.SELL, ReverseInfiniteStrategy.LOC_SELL_LEG));
        assertThat(strategy.canSkipOrderComputation(orders, AT_CLOSE)).isTrue();
    }

    @Test
    @DisplayName("AT_CLOSE 리버스 LOC_BUY만 있거나 LOC_SELL만 있으면 false")
    void atClose_reverseOnlyOne_false() {
        var buyOnly = List.of(closeBuy(ReverseInfiniteStrategy.LOC_BUY_LEG));
        var sellOnly = List.of(order(OrderTiming.AT_CLOSE, OrderDirection.SELL, ReverseInfiniteStrategy.LOC_SELL_LEG));
        assertThat(strategy.canSkipOrderComputation(buyOnly, AT_CLOSE)).isFalse();
        assertThat(strategy.canSkipOrderComputation(sellOnly, AT_CLOSE)).isFalse();
    }

    @Test
    @DisplayName("AT_CLOSE LOC 매도(INFINITE_LOC_SELL) 유무는 판정에 영향 없음")
    void atClose_locSellDoesNotAffect() {
        var locSell = order(OrderTiming.AT_CLOSE, OrderDirection.SELL, "INFINITE_LOC_SELL");
        var complete = with(corrections(), closeBuy(InfiniteStrategy.EARLY_MERGED_LEG), locSell);
        var incomplete = with(corrections(), locSell);

        assertThat(strategy.canSkipOrderComputation(complete, AT_CLOSE)).isTrue();
        assertThat(strategy.canSkipOrderComputation(incomplete, AT_CLOSE)).isFalse();
    }

    @Test
    @DisplayName("AT_OPEN이 creatable이면 슬롯이 있어도 false (현재 동작 고정: atOpenComplete = !hasAtOpenTiming)")
    void atOpen_creatable_alwaysFalse() {
        var limitSell = order(OrderTiming.AT_OPEN, OrderDirection.SELL, "INFINITE_LIMIT_SELL");

        assertThat(strategy.canSkipOrderComputation(List.of(limitSell), AT_OPEN)).isFalse();
        // creatable(AT_OPEN)과 겹치는 기존 주문이 없으면 조기 false
        assertThat(strategy.canSkipOrderComputation(List.of(closeBuy(InfiniteStrategy.EARLY_AVG_LEG)), AT_OPEN)).isFalse();
        // AT_OPEN·AT_CLOSE 둘 다 creatable이면 AT_CLOSE가 완전해도 false
        var orders = with(corrections(), closeBuy(InfiniteStrategy.EARLY_MERGED_LEG), limitSell);
        assertThat(strategy.canSkipOrderComputation(orders, Set.of(OrderTiming.AT_OPEN, OrderTiming.AT_CLOSE))).isFalse();
    }

    @Test
    @DisplayName("기존 주문이 비어 있으면 false")
    void emptyExistingOrders_false() {
        assertThat(strategy.canSkipOrderComputation(List.of(), AT_CLOSE)).isFalse();
        assertThat(strategy.canSkipOrderComputation(List.of(), AT_OPEN)).isFalse();
    }
}
