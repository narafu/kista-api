package com.kista.shared.kernel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StrategyType.capability() — 전략 capability 상수 SSOT")
class StrategyTypeCapabilityTest {

    @Test
    @DisplayName("INFINITE: 전체 티커, 고정 아님, 리버스모드 지원, 분할 수 20/30/40")
    void infinite() {
        StrategyCapability c = StrategyType.INFINITE.capability();
        assertThat(c.availableTickers())
                .containsExactly(StrategyTicker.MAGX, StrategyTicker.USD, StrategyTicker.TQQQ, StrategyTicker.SOXL);
        assertThat(c.tickerFixed()).isFalse();
        assertThat(c.requiresPrivacyBase()).isFalse();
        assertThat(c.supportsReverseMode()).isTrue();
        assertThat(c.divisionCounts()).containsExactly(20, 30, 40);
    }

    @Test
    @DisplayName("PRIVACY: SOXL 고정, 기준 매매표 필요, 리버스모드·분할 없음")
    void privacy() {
        StrategyCapability c = StrategyType.PRIVACY.capability();
        assertThat(c.availableTickers()).containsExactly(StrategyTicker.SOXL);
        assertThat(c.tickerFixed()).isTrue();
        assertThat(c.requiresPrivacyBase()).isTrue();
        assertThat(c.supportsReverseMode()).isFalse();
        assertThat(c.divisionCounts()).isEmpty();
    }

    @Test
    @DisplayName("VR: TQQQ 고정, 기준 매매표·리버스모드·분할 없음")
    void vr() {
        StrategyCapability c = StrategyType.VR.capability();
        assertThat(c.availableTickers()).containsExactly(StrategyTicker.TQQQ);
        assertThat(c.tickerFixed()).isTrue();
        assertThat(c.requiresPrivacyBase()).isFalse();
        assertThat(c.supportsReverseMode()).isFalse();
        assertThat(c.divisionCounts()).isEmpty();
    }

    @Test
    @DisplayName("availableTickers()는 capability()에 위임하고 tickerFixed는 단일 티커 여부와 일치한다")
    void availableTickersDelegatesAndFixedMatchesSize() {
        for (StrategyType type : StrategyType.values()) {
            assertThat(type.availableTickers()).isEqualTo(type.capability().availableTickers());
            assertThat(type.capability().tickerFixed()).isEqualTo(type.availableTickers().size() == 1);
        }
    }

    @Test
    @DisplayName("빈 availableTickers(Set.of())도 예외 없이 빈 집합으로 방어 복사한다")
    void emptyAvailableTickersIsAccepted() {
        StrategyCapability c = StrategyCapability.basic(Set.of(), false, false, false, List.of());
        assertThat(c.availableTickers()).isEmpty();
        assertThat(c.divisionCounts()).isEmpty();
    }

    @Test
    void dca_capability_exposes_dca_choices() {
        StrategyCapability dca = StrategyType.DCA.capability();
        assertThat(dca.availableTickers()).containsExactly(StrategyTicker.UPRO, StrategyTicker.QLD, StrategyTicker.BTC, StrategyTicker.ETH);
        assertThat(dca.tickerFixed()).isFalse();
        assertThat(dca.divisionCounts()).isEmpty();
        assertThat(dca.orderTypes()).containsExactly(OrderType.MOO, OrderType.MOC, OrderType.MARKET);
        assertThat(dca.directions()).containsExactly(OrderDirection.BUY, OrderDirection.SELL);
        assertThat(dca.frequencies()).containsExactly(DcaFrequency.values());
        assertThat(dca.conditionIndicators()).containsExactly(DcaIndicator.AVG_PRICE, DcaIndicator.SMA, DcaIndicator.RSI, DcaIndicator.FEAR_GREED);
    }

    @Test
    void non_dca_types_have_empty_dca_choices() {
        for (StrategyType type : List.of(StrategyType.INFINITE, StrategyType.PRIVACY, StrategyType.VR)) {
            StrategyCapability c = type.capability();
            assertThat(c.orderTypes()).isEmpty();
            assertThat(c.directions()).isEmpty();
            assertThat(c.frequencies()).isEmpty();
            assertThat(c.conditionIndicators()).isEmpty();
        }
    }

    @Test
    void operator_compares_by_value() {
        assertThat(DcaOperator.LTE.test(new BigDecimal("10.0"), new BigDecimal("10"))).isTrue();
        assertThat(DcaOperator.LT.test(new BigDecimal("10"), new BigDecimal("10"))).isFalse();
        assertThat(DcaOperator.GTE.test(new BigDecimal("10"), new BigDecimal("10.00"))).isTrue();
        assertThat(DcaOperator.GT.test(new BigDecimal("11"), new BigDecimal("10"))).isTrue();
    }
}
