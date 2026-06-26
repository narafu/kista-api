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
    }

    @Test
    void privacy_capabilities() {
        var privacy = new PrivacyCycleOrderStrategy(null);
        assertThat(privacy.supportsReverseMode()).isFalse();
        assertThat(privacy.availableDivisionCounts()).isEmpty();
        assertThat(privacy.requiresPrivacyBase()).isTrue();
    }
}
