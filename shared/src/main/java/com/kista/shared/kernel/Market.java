package com.kista.shared.kernel;

import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

// 거래 시장 — 증권사(Broker)와 종목(StrategyTicker)이 같은 시장이어야 전략을 만들 수 있다. 상수명은 API 계약(변경 금지)
@RequiredArgsConstructor
public enum Market {
    US_STOCK(Currency.USD, false, BigDecimal.ZERO),                    // 미국 주식 (KIS/Toss/MOCK) — 금액 하한 없음, 1주 단위 판정
    CRYPTO_KRW(Currency.KRW, true, new BigDecimal("5000")),            // 원화 코인 마켓 (UPBIT)
    CRYPTO_USDT(Currency.USDT, true, new BigDecimal("5"));             // USDT 코인 마켓 (바이낸스 BTCUSDT·ETHUSDT NOTIONAL 5 USDT 실측)

    private final Currency quoteCurrency;   // 시세·주문 금액 기준 통화
    private final boolean crypto;           // 코인 시장 여부
    private final BigDecimal minOrderAmount; // 등록 시점 정적 최소 주문 금액 (scale 0 리터럴 — 문구 "5000"/"5" 보존)

    public Currency quoteCurrency() {
        return quoteCurrency;
    }

    public boolean isCrypto() {
        return crypto;
    }

    public BigDecimal minOrderAmount() {
        return minOrderAmount;
    }

    // 금액 scale은 견적 통화 SSOT에 위임 (주식 DCA 금액 scale은 강제하지 않는다 — 기존 동작)
    public int amountScale() {
        return quoteCurrency.amountScale();
    }
}
