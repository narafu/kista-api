package com.kista.domain.port.out;

import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossCandle;

import java.time.LocalDate;
import java.util.List;

// 공통 API — 개별 계좌 토큰 불필요, 관리자 자격증명으로 조회
public interface TosCandlePort {
    // GET /api/v1/candles — interval 예: "1d"(일봉), "1w"(주봉)
    List<TossCandle> getCandles(Ticker ticker, String interval, LocalDate from, LocalDate to);
}
