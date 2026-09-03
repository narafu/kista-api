package com.kista.application.port.output;

import com.kista.domain.model.backtest.DailyCandle;

import java.time.LocalDate;
import java.util.List;

// 외부 시세 제공자에서 과거 일봉 OHLC 조회 (Alpaca, 수정주가) — 백테스트 시뮬레이션 입력용
public interface HistoricalCandlePort {
    List<DailyCandle> fetchDailyCandles(String symbol, LocalDate from, LocalDate to);
}
