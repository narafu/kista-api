package com.kista.domain.strategy;

import java.math.BigDecimal;

import static java.math.RoundingMode.HALF_UP;

// 매수 가격 캡 공통 정책 — INFINITE/PRIVACY(BuyOrderPriceCapper)·VR(VrStrategy) 매수 사다리 공용
public final class PriceCapPolicy {

    // 캡 배수: currentPrice × (1+RATE) 초과 매수가는 캡으로 대체
    private static final BigDecimal CAP_MULTIPLIER = new BigDecimal("1.05");

    private PriceCapPolicy() {
    }

    // currentPrice 기준 캡 가격 계산
    public static BigDecimal capFor(BigDecimal currentPrice) {
        return currentPrice.multiply(CAP_MULTIPLIER).setScale(2, HALF_UP);
    }

    // price가 cap 초과면 cap으로 교체
    public static BigDecimal applyCap(BigDecimal price, BigDecimal cap) {
        return price.compareTo(cap) > 0 ? cap : price;
    }
}
