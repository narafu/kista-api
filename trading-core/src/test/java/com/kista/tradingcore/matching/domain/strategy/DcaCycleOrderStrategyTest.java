package com.kista.tradingcore.matching.domain.strategy;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import com.kista.tradingcore.matching.domain.model.DcaDecision;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DcaCycleOrderStrategyTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private final DcaCycleOrderStrategy strategy = new DcaCycleOrderStrategy();

    private static DcaConfig config(OrderDirection dir, String amount, OrderType type) {
        return new DcaConfig(dir, new BigDecimal(amount), type, DcaFrequency.DAILY, null, null);
    }

    private DcaDecision decide(DcaConfig c, String prevClose, Integer sellable) {
        return strategy.decide(new DcaCycleOrderStrategy.DcaInputs(c, StrategyTicker.UPRO, DAY,
                prevClose == null ? null : new BigDecimal(prevClose), sellable));
    }

    @Test
    void buy_quantity_is_floor_of_amount_over_prev_close_with_cap_price() {
        DcaDecision d = decide(config(OrderDirection.BUY, "300", OrderType.MOC), "87.12", null);
        PlannedOrder o = ((DcaDecision.Planned) d).order();
        assertThat(o.quantity()).isEqualTo(3);
        assertThat(o.price()).isEqualByComparingTo("91.48"); // 87.12 × 1.05 HALF_UP — PriceCapPolicy 결과와 일치해야 함
        assertThat(o.orderType()).isEqualTo(OrderType.MOC);
        assertThat(o.timing()).isEqualTo(OrderTiming.AT_CLOSE);
        assertThat(o.direction()).isEqualTo(OrderDirection.BUY);
        assertThat(o.orderLeg()).isEqualTo("DCA_BUY");
    }

    @Test
    void moo_is_at_open() {
        PlannedOrder o = ((DcaDecision.Planned) decide(config(OrderDirection.BUY, "300", OrderType.MOO), "87.12", null)).order();
        assertThat(o.timing()).isEqualTo(OrderTiming.AT_OPEN);
    }

    @Test
    void below_one_share_skips() {
        DcaDecision d = decide(config(OrderDirection.BUY, "50", OrderType.MOC), "87.12", null);
        assertThat(d).isEqualTo(new DcaDecision.Skipped(DcaSkipReason.BELOW_ONE_SHARE, "회차 금액 50 < 전일 종가 87.12", new BigDecimal("87.12")));
    }

    @Test
    void missing_prev_close_is_data_unavailable() {
        assertThat(((DcaDecision.Skipped) decide(config(OrderDirection.BUY, "300", OrderType.MOC), null, null)).reason())
                .isEqualTo(DcaSkipReason.DATA_UNAVAILABLE);
    }

    @Test
    void sell_caps_to_sellable_and_uses_prev_close_price() {
        PlannedOrder o = ((DcaDecision.Planned) decide(config(OrderDirection.SELL, "1000", OrderType.MOC), "100", 4)).order();
        assertThat(o.quantity()).isEqualTo(4);
        assertThat(o.price()).isEqualByComparingTo("100");
        assertThat(o.orderLeg()).isEqualTo("DCA_SELL");
    }

    @Test
    void sell_with_no_sellable_skips_no_holdings() {
        assertThat(((DcaDecision.Skipped) decide(config(OrderDirection.SELL, "1000", OrderType.MOC), "100", 0)).reason())
                .isEqualTo(DcaSkipReason.NO_HOLDINGS);
    }

    @Test
    void sell_without_sellable_data_is_data_unavailable() {
        assertThat(((DcaDecision.Skipped) decide(config(OrderDirection.SELL, "1000", OrderType.MOC), "100", null)).reason())
                .isEqualTo(DcaSkipReason.DATA_UNAVAILABLE);
    }

    @Test
    void fitBuysToBudget_shrinks_by_cap_price() {
        PlannedOrder buy = PlannedOrder.of(DAY, StrategyTicker.UPRO, OrderType.MOC, OrderDirection.BUY, 20,
                new BigDecimal("105"), OrderTiming.AT_CLOSE, "DCA_BUY");
        List<PlannedOrder> fitted = strategy.fitBuysToBudget(List.of(buy), new BigDecimal("1000"));
        assertThat(fitted).singleElement().extracting(PlannedOrder::quantity).isEqualTo(9);
    }

    @Test
    void fitBuysToBudget_returns_empty_below_one_share() {
        PlannedOrder buy = PlannedOrder.of(DAY, StrategyTicker.UPRO, OrderType.MOC, OrderDirection.BUY, 3,
                new BigDecimal("105"), OrderTiming.AT_CLOSE, "DCA_BUY");
        assertThat(strategy.fitBuysToBudget(List.of(buy), new BigDecimal("100"))).isEmpty();
    }

    @Test
    void policy_flags() {
        assertThat(strategy.endsCycleOnLiquidation()).isFalse();
        assertThat(strategy.needsCapCheck(null, null)).isFalse();
        assertThat(strategy.allocationPriority()).isEqualTo(200);
        assertThat(strategy.snapshotsBrokerBalance()).isTrue();
    }
}
