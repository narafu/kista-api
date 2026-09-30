package com.kista.sharedkernel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StrategyType.capability() — 전략 capability 상수 SSOT")
class StrategyTypeCapabilityTest {

    @Test
    @DisplayName("INFINITE: 전체 티커, 고정 아님, 리버스모드 지원, 분할 수 20/30/40")
    void infinite() {
        StrategyCapability c = StrategyType.INFINITE.capability();
        assertThat(c.availableTickers()).containsExactlyInAnyOrder(StrategyTicker.values());
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
        StrategyCapability c = new StrategyCapability(Set.of(), false, false, false, List.of());
        assertThat(c.availableTickers()).isEmpty();
        assertThat(c.divisionCounts()).isEmpty();
    }
}
