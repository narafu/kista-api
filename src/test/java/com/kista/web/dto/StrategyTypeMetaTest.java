package com.kista.web.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import com.kista.sharedkernel.StrategyType;

class StrategyTypeMetaTest {

    @Test
    void infinite_meta_has_capabilities() {
        var meta = StrategyTypeMeta.from(StrategyType.INFINITE);
        assertThat(meta.requiresPrivacyBase()).isFalse();
        assertThat(meta.tickerFixed()).isFalse();        // INFINITE: availableTickers > 1
        assertThat(meta.supportsReverseMode()).isTrue();
        assertThat(meta.divisionCounts()).containsExactly(20, 30, 40);
    }

    @Test
    void privacy_meta_has_capabilities() {
        var meta = StrategyTypeMeta.from(StrategyType.PRIVACY);
        assertThat(meta.requiresPrivacyBase()).isTrue();
        assertThat(meta.tickerFixed()).isTrue();          // PRIVACY: SOXL 단일
        assertThat(meta.supportsReverseMode()).isFalse();
        assertThat(meta.divisionCounts()).isEmpty();
    }

    @Test
    void vr_meta_has_capabilities() {
        var meta = StrategyTypeMeta.from(StrategyType.VR);
        assertThat(meta.code()).isEqualTo("VR");
        assertThat(meta.availableTickers()).containsExactly("TQQQ"); // VR: TQQQ 단일
        assertThat(meta.tickerFixed()).isTrue();                     // 단일 ticker → 고정
        assertThat(meta.requiresPrivacyBase()).isFalse();
        assertThat(meta.supportsReverseMode()).isFalse();
        assertThat(meta.divisionCounts()).isEmpty();
    }
}
