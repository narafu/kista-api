package com.kista.broker.application.port.output;

import com.kista.broker.domain.model.BrokerCandle;

import java.time.LocalDate;
import java.util.List;

// 캔들 조회 (현재 Toss만 구현) — 공통 API, Account 토큰 불필요. 벤더 중립 BrokerCandle 반환
public interface CandlePort {
    List<BrokerCandle> getCandles(String symbol, String interval, LocalDate from, LocalDate to);
    List<BrokerCandle> getLatestCandles(String symbol, String interval, int count);
}
