package com.kista.tradingcore.broker.application.port.output;

import com.kista.tradingcore.broker.domain.model.BrokerCandle;

import java.time.LocalDate;
import java.util.List;

// 캔들 조회 (현재 Toss만 구현) — 공통 API, Account 토큰 불필요. 벤더 중립 BrokerCandle 반환
public interface CandlePort {
    List<BrokerCandle> getCandles(String symbol, String interval, LocalDate from, LocalDate to);
    List<BrokerCandle> getLatestCandles(String symbol, String interval, int count);
    // beforeUsSession(미포함) 이전 확정 일봉 최대 count개, 오름차순 — 진행 중인 당일 봉 배제(DCA 전일 종가·지표 공용)
    List<BrokerCandle> getDailyCandlesBefore(String symbol, LocalDate beforeUsSession, int count);
}
