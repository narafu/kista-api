package com.kista.tradingcore.trading.application.port.output;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

// CNN 공포탐욕지수 — DCA FEAR_GREED 조건용 (spec 6.2)
public interface FearGreedIndexPort {

    // usSessionDate 세션 마감 기준 지수(소수 2자리) — 조회 실패·해당 일자 점 없음이면 empty
    Optional<BigDecimal> closeOn(LocalDate usSessionDate);
}
