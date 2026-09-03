package com.kista.broker.domain.port.out;

import com.kista.broker.domain.model.toss.TossCandle;

import java.time.LocalDate;
import java.util.List;

// 캔들 조회 (Toss 전용) — 공통 API, Account 토큰 불필요
public interface CandlePort {
    List<TossCandle> getCandles(String symbol, String interval, LocalDate from, LocalDate to);
    List<TossCandle> getLatestCandles(String symbol, String interval, int count);
}
