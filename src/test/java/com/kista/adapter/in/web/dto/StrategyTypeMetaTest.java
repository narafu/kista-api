package com.kista.adapter.in.web.dto;

import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.strategy.CycleOrderStrategies;
import com.kista.domain.strategy.InfiniteCycleOrderStrategy;
import com.kista.domain.strategy.PrivacyCycleOrderStrategy;
import com.kista.domain.strategy.VrCycleOrderStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyTypeMetaTest {

    private CycleOrderStrategies strategies() {
        return new CycleOrderStrategies(List.of(
                new InfiniteCycleOrderStrategy(null, null),
                new PrivacyCycleOrderStrategy(null),
                new VrCycleOrderStrategy(null)
        ));
    }

    @Test
    void infinite_meta_has_capabilities() {
        var s = strategies().of(Strategy.Type.INFINITE);
        var meta = StrategyTypeMeta.from(Strategy.Type.INFINITE, s);
        assertThat(meta.requiresPrivacyBase()).isFalse();
        assertThat(meta.tickerFixed()).isFalse();        // INFINITE: availableTickers > 1
        assertThat(meta.supportsReverseMode()).isTrue();
        assertThat(meta.divisionCounts()).containsExactly(20);
    }

    @Test
    void privacy_meta_has_capabilities() {
        var s = strategies().of(Strategy.Type.PRIVACY);
        var meta = StrategyTypeMeta.from(Strategy.Type.PRIVACY, s);
        assertThat(meta.requiresPrivacyBase()).isTrue();
        assertThat(meta.tickerFixed()).isTrue();          // PRIVACY: SOXL 단일
        assertThat(meta.supportsReverseMode()).isFalse();
        assertThat(meta.divisionCounts()).isEmpty();
    }

    @Test
    void vr_meta_has_capabilities() {
        var s = strategies().of(Strategy.Type.VR);
        var meta = StrategyTypeMeta.from(Strategy.Type.VR, s);
        assertThat(meta.code()).isEqualTo("VR");
        assertThat(meta.availableTickers()).containsExactly("TQQQ"); // VR: TQQQ 단일
        assertThat(meta.tickerFixed()).isTrue();                     // 단일 ticker → 고정
        assertThat(meta.requiresPrivacyBase()).isFalse();
        assertThat(meta.supportsReverseMode()).isFalse();
        assertThat(meta.divisionCounts()).isEmpty();
    }
}
