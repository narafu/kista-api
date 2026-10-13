package com.kista.tradingcore.trading.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CyclePositionTest {

    // 테스트용 포지션 생성 — 예수금 100
    private static CyclePosition position(BigDecimal closingPrice, BigDecimal avgPrice, int holdings) {
        return new CyclePosition(null, null, new BigDecimal("100"), closingPrice, avgPrice, holdings, null, null);
    }

    @Test
    void 종가_평가_총자산은_보유분이_있는데_종가가_없으면_null() {
        assertThat(position(null, new BigDecimal("10"), 3).totalAssetAtCloseOrNull()).isNull();
    }

    @Test
    void 종가_평가_총자산은_보유분이_없으면_종가_없어도_예수금() {
        assertThat(position(null, null, 0).totalAssetAtCloseOrNull()).isEqualByComparingTo("100");
    }

    @Test
    void 종가_평가_총자산은_반올림하지_않는다() {
        assertThat(position(new BigDecimal("10.005"), null, 1).totalAssetAtCloseOrNull())
                .isEqualTo(new BigDecimal("110.005"));
    }

    @Test
    void 대체_평가_총자산은_종가_없으면_평단가로_평가하고_scale2_반올림() {
        assertThat(position(null, new BigDecimal("10.005"), 1).totalAssetWithAvgPriceFallback())
                .isEqualTo(new BigDecimal("110.01"));
    }

    @Test
    void 대체_평가_총자산은_종가가_평단가보다_우선() {
        assertThat(position(new BigDecimal("12"), new BigDecimal("10"), 2).totalAssetWithAvgPriceFallback())
                .isEqualTo(new BigDecimal("124.00"));
    }

    @Test
    void 대체_평가_총자산은_종가_평단가_모두_없으면_예수금() {
        assertThat(position(null, null, 3).totalAssetWithAvgPriceFallback()).isEqualTo(new BigDecimal("100.00"));
    }
}
