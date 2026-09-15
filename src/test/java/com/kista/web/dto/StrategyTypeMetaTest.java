package com.kista.web.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import com.kista.sharedkernel.StrategyType;

class StrategyTypeMetaTest {

    @Test
    void infinite_meta_has_capabilities() {
        var capability = new StrategyCapability(false, true, List.of(20, 30, 40));
        var meta = StrategyTypeMeta.from(StrategyType.INFINITE, capability);
        assertThat(meta.requiresPrivacyBase()).isFalse();
        assertThat(meta.tickerFixed()).isFalse();        // INFINITE: availableTickers > 1
        assertThat(meta.supportsReverseMode()).isTrue();
        assertThat(meta.divisionCounts()).containsExactly(20, 30, 40);
    }

    @Test
    void privacy_meta_has_capabilities() {
        var capability = new StrategyCapability(true, false, List.of());
        var meta = StrategyTypeMeta.from(StrategyType.PRIVACY, capability);
        assertThat(meta.requiresPrivacyBase()).isTrue();
        assertThat(meta.tickerFixed()).isTrue();          // PRIVACY: SOXL 단일
        assertThat(meta.supportsReverseMode()).isFalse();
        assertThat(meta.divisionCounts()).isEmpty();
    }

    @Test
    void vr_meta_has_capabilities() {
        var capability = new StrategyCapability(false, false, List.of());
        var meta = StrategyTypeMeta.from(StrategyType.VR, capability);
        assertThat(meta.code()).isEqualTo("VR");
        assertThat(meta.availableTickers()).containsExactly("TQQQ"); // VR: TQQQ 단일
        assertThat(meta.tickerFixed()).isTrue();                     // 단일 ticker → 고정
        assertThat(meta.requiresPrivacyBase()).isFalse();
        assertThat(meta.supportsReverseMode()).isFalse();
        assertThat(meta.divisionCounts()).isEmpty();
    }
}
