package com.kista.tradingcore.trading.application.port.output;

import java.math.BigDecimal;
import java.util.Optional;

// CNN 공포탐욕지수 — DCA FEAR_GREED 조건용 (spec 6.2)
public interface FearGreedIndexPort {

    // 조회 시점 최신 지수(소수 2자리) — 조회 실패·값 없음이면 empty
    Optional<BigDecimal> latest();
}
