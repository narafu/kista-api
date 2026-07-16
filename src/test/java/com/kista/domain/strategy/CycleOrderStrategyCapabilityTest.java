package com.kista.domain.strategy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CycleOrderStrategyCapabilityTest {

    @Test
    void infinite_capabilities() {
        var infinite = new InfiniteCycleOrderStrategy(null, null);
        assertThat(infinite.supportsReverseMode()).isTrue();
        assertThat(infinite.availableDivisionCounts()).containsExactly(20);
        assertThat(infinite.requiresPrivacyBase()).isFalse();
        assertThat(infinite.requiresPrevClose()).isTrue();
        assertThat(infinite.endsCycleOnLiquidation()).isTrue(); // 기본값 true
        assertThat(infinite.tracksReverseMode()).isTrue();
        assertThat(infinite.requiresRolloverCheck()).isFalse(); // 기본값
        assertThat(infinite.priceCapMode()).isEqualTo(com.kista.domain.strategy.CycleOrderStrategy.PriceCapMode.INFINITE_POSITION);
        assertThat(infinite.allocationPriority()).isEqualTo(1);
    }

    @Test
    void privacy_capabilities() {
        var privacy = new PrivacyCycleOrderStrategy(null);
        assertThat(privacy.supportsReverseMode()).isFalse();
        assertThat(privacy.availableDivisionCounts()).isEmpty();
        assertThat(privacy.requiresPrivacyBase()).isTrue();
        assertThat(privacy.endsCycleOnLiquidation()).isTrue(); // 기본값 true
        assertThat(privacy.tracksReverseMode()).isFalse(); // 기본값
        assertThat(privacy.requiresRolloverCheck()).isFalse(); // 기본값
        assertThat(privacy.priceCapMode()).isEqualTo(com.kista.domain.strategy.CycleOrderStrategy.PriceCapMode.PRIVACY_SIMPLE);
        assertThat(privacy.allocationPriority()).isEqualTo(2);
    }

    @Test
    void vr_capabilities() {
        var vr = new VrCycleOrderStrategy(null);
        assertThat(vr.supportsReverseMode()).isFalse();
        assertThat(vr.availableDivisionCounts()).isEmpty();
        assertThat(vr.requiresPrivacyBase()).isFalse();
        assertThat(vr.requiresPrevClose()).isFalse();
        assertThat(vr.endsCycleOnLiquidation()).isFalse(); // VR만 false — 전량 청산 후에도 사이클 유지
        assertThat(vr.tracksReverseMode()).isFalse(); // 기본값
        assertThat(vr.requiresRolloverCheck()).isTrue();
        assertThat(vr.priceCapMode()).isEqualTo(com.kista.domain.strategy.CycleOrderStrategy.PriceCapMode.NONE); // 기본값
        assertThat(vr.allocationPriority()).isZero();
    }
}
