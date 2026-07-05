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
    }

    @Test
    void privacy_capabilities() {
        var privacy = new PrivacyCycleOrderStrategy(null);
        assertThat(privacy.supportsReverseMode()).isFalse();
        assertThat(privacy.availableDivisionCounts()).isEmpty();
        assertThat(privacy.requiresPrivacyBase()).isTrue();
        assertThat(privacy.endsCycleOnLiquidation()).isTrue(); // 기본값 true
    }

    @Test
    void vr_capabilities() {
        var vr = new VrCycleOrderStrategy(null);
        assertThat(vr.supportsReverseMode()).isFalse();
        assertThat(vr.availableDivisionCounts()).isEmpty();
        assertThat(vr.requiresPrivacyBase()).isFalse();
        assertThat(vr.requiresPrevClose()).isFalse();
        assertThat(vr.endsCycleOnLiquidation()).isFalse(); // VR만 false — 전량 청산 후에도 사이클 유지
    }
}
