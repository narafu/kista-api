package com.kista.shared.kernel;

// 거래 시장 — 증권사(Broker)와 종목(StrategyTicker)이 같은 시장이어야 전략을 만들 수 있다. 상수명은 API 계약(변경 금지)
public enum Market {
    US_STOCK,   // 미국 주식 (KIS/Toss/MOCK)
    CRYPTO_KRW  // 원화 코인 마켓 (UPBIT)
}
