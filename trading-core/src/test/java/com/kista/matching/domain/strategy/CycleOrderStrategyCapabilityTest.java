package com.kista.matching.domain.strategy;

import com.kista.matching.domain.model.AccountBalance;
import com.kista.matching.domain.model.InfinitePosition;
import com.kista.matching.domain.model.VrPosition;
import com.kista.sharedkernel.StrategyTicker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CycleOrderStrategyCapabilityTest {

    static final InfinitePosition POSITION = new InfinitePosition(
            new AccountBalance(0, null, new BigDecimal("20000")), StrategyTicker.SOXL, new BigDecimal("10.00"), 20);

    static final VrPosition VR_POSITION = new VrPosition(
            new AccountBalance(1, new BigDecimal("100.00"), new BigDecimal("5000.00")),
            new BigDecimal("10000.00"), new BigDecimal("15.00"), new BigDecimal("5000.00"), BigDecimal.ZERO, 0);

    @Test
    void infinite_capabilities() {
        var infinite = new InfiniteCycleOrderStrategy(null, null);
        assertThat(infinite.supportsReverseMode()).isTrue();
        assertThat(infinite.availableDivisionCounts()).containsExactly(20, 30, 40);
        assertThat(infinite.requiresPrivacyBase()).isFalse();
        assertThat(infinite.requiresPrevClose()).isTrue();
        assertThat(infinite.endsCycleOnLiquidation()).isTrue(); // 기본값 true
        assertThat(infinite.tracksReverseMode()).isTrue();
        assertThat(infinite.requiresRolloverCheck()).isFalse(); // 기본값
        assertThat(infinite.capsIndividualOrders()).isFalse(); // 기본값 — 사다리 전체 취소·재저장
        assertThat(infinite.needsCapCheck(null, null)).isFalse(); // position 없으면 캡 검사 스킵
        assertThat(infinite.needsCapCheck(POSITION, null)).isTrue();
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
        assertThat(privacy.capsIndividualOrders()).isTrue(); // 개별 주문 가격만 치환
        assertThat(privacy.needsCapCheck(null, null)).isTrue(); // 기본값 — position/vrPosition 불필요
        assertThat(privacy.allocationPriority()).isEqualTo(2);
    }

    @Test
    void vr_capabilities() {
        var vr = new VrCycleOrderStrategy(null);
        assertThat(vr.supportsReverseMode()).isFalse();
        assertThat(vr.availableDivisionCounts()).isEmpty();
        assertThat(vr.requiresPrivacyBase()).isFalse();
        assertThat(vr.requiresPrevClose()).isTrue();
        assertThat(vr.endsCycleOnLiquidation()).isFalse(); // VR만 false — 전량 청산 후에도 사이클 유지
        assertThat(vr.tracksReverseMode()).isFalse(); // 기본값
        assertThat(vr.requiresRolloverCheck()).isTrue();
        assertThat(vr.capsIndividualOrders()).isFalse(); // 기본값 — 사다리 전체 취소·재저장
        assertThat(vr.needsCapCheck(null, null)).isFalse(); // vrPosition 없으면 캡 검사 스킵
        assertThat(vr.needsCapCheck(null, VR_POSITION)).isTrue();
        assertThat(vr.allocationPriority()).isZero();
    }
}
