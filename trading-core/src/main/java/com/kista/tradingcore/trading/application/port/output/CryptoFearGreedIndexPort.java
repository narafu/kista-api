package com.kista.tradingcore.trading.application.port.output;

import java.math.BigDecimal;
import java.util.Optional;

// 코인 공포탐욕지수 — 코인 DCA FEAR_GREED 조건용 (alternative.me, 주식 FearGreedIndexPort와 분리)
public interface CryptoFearGreedIndexPort {

    // 조회 시점 최신 지수(소수 2자리) — 조회 실패·값 없음이면 empty
    Optional<BigDecimal> latest();
}
