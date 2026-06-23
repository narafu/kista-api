package com.kista.domain.port.out.broker;

import com.kista.domain.model.toss.TossCandle;

import java.time.LocalDate;
import java.util.List;

// 캔들 조회 (Toss 전용) — 공통 API, Account 토큰 불필요
public interface CandleCapable {
    List<TossCandle> getCandles(String symbol, String interval, LocalDate from, LocalDate to);
    List<TossCandle> getLatestCandles(String symbol, String interval, int count);
}
