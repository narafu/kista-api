package com.kista.shared.kernel;

import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

// 거래 시장 — 증권사(Broker)와 종목(StrategyTicker)이 같은 시장이어야 전략을 만들 수 있다. 상수명은 API 계약(변경 금지)
@RequiredArgsConstructor
public enum Market {
    US_STOCK(Currency.USD, BigDecimal.ZERO); // 미국 주식 (Toss/Meritz/MOCK) — 금액 하한 없음, 1주 단위 판정

    private final Currency quoteCurrency;   // 시세·주문 금액 기준 통화
    private final BigDecimal minOrderAmount; // 등록 시점 정적 최소 주문 금액

    public Currency quoteCurrency() {
        return quoteCurrency;
    }

    public BigDecimal minOrderAmount() {
        return minOrderAmount;
    }

    // 금액 scale은 견적 통화 SSOT에 위임 (주식 DCA 금액 scale은 강제하지 않는다 — 기존 동작)
    public int amountScale() {
        return quoteCurrency.amountScale();
    }
}
