package com.kista.privacy.domain.model;

import com.kista.matching.domain.model.PrivacyPlan;
import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.StrategyTicker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PrivacyTradeBase / PrivacyCurrentBase 불변식 검증")
class PrivacyTradeBaseTest {

    @Test
    @DisplayName("PrivacyTradeBase: currentCycleStart=null이면 생성 실패")
    void privacyTradeBase_nullCurrentCycleStart_throws() {
        assertThatThrownBy(() -> new PrivacyTradeBase(UUID.randomUUID(), new BigDecimal("20.00"), 10, null, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("currentCycleStart 이상");
    }

    @Test
    @DisplayName("PrivacyTradeBase: currentCycleStart<=0이면 생성 실패")
    void privacyTradeBase_nonPositiveCurrentCycleStart_throws() {
        assertThatThrownBy(() -> new PrivacyTradeBase(UUID.randomUUID(), new BigDecimal("20.00"), 10, BigDecimal.ZERO, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("currentCycleStart 이상");
    }

    @Test
    @DisplayName("PrivacyCurrentBase: currentCycleStart=null이면 생성 실패")
    void privacyCurrentBase_nullCurrentCycleStart_throws() {
        assertThatThrownBy(() -> new PrivacyCurrentBase(StrategyTicker.SOXL, null, LocalDate.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("currentCycleStart 이상");
    }

    @Test
    @DisplayName("PrivacyCurrentBase: currentCycleStart<=0이면 생성 실패")
    void privacyCurrentBase_nonPositiveCurrentCycleStart_throws() {
        assertThatThrownBy(() -> new PrivacyCurrentBase(StrategyTicker.SOXL, new BigDecimal("-1"), LocalDate.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("currentCycleStart 이상");
    }

    @Test
    @DisplayName("PrivacyTradeBase.toPlan(): 커널 입력(PrivacyPlan)으로 필드·주문을 그대로 옮긴다")
    void toPlan_mapsFieldsAndTrades() {
        LocalDate date = LocalDate.of(2026, 5, 26);
        PrivacyTradeBase base = new PrivacyTradeBase(UUID.randomUUID(), new BigDecimal("20.00"), 240,
                new BigDecimal("1000"), List.of(
                new PrivacyTradeBase.PrivacyTrade(date, StrategyTicker.SOXL, OrderType.LOC, OrderDirection.BUY, 100, new BigDecimal("10")),
                new PrivacyTradeBase.PrivacyTrade(date, StrategyTicker.SOXL, OrderType.LIMIT, OrderDirection.SELL, null, new BigDecimal("12"))));

        PrivacyPlan plan = base.toPlan();

        assertThat(plan.holdings()).isEqualTo(240);
        assertThat(plan.currentCycleStart()).isEqualByComparingTo("1000");
        assertThat(plan.trades()).containsExactly(
                new PrivacyPlan.PrivacyPlannedTrade(date, StrategyTicker.SOXL, OrderType.LOC, OrderDirection.BUY, 100, new BigDecimal("10")),
                new PrivacyPlan.PrivacyPlannedTrade(date, StrategyTicker.SOXL, OrderType.LIMIT, OrderDirection.SELL, null, new BigDecimal("12")));
    }

    @Test
    @DisplayName("PrivacyPlan: currentCycleStart<=0이면 생성 실패 (PrivacyTradeBase 불변식 미러)")
    void privacyPlan_nonPositiveCurrentCycleStart_throws() {
        assertThatThrownBy(() -> new PrivacyPlan(10, BigDecimal.ZERO, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("currentCycleStart 이상");
    }
}
