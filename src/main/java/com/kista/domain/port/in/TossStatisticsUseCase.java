package com.kista.domain.port.in;

import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossAccountInfo;
import com.kista.domain.model.toss.TossCandle;
import com.kista.domain.model.toss.TossExchangeRate;
import com.kista.domain.model.toss.TossMarketSession;
import com.kista.domain.model.toss.TossStockInfo;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Toss 전용 통계 기능 — Toss 계좌에서만 접근 가능 (비Toss 호출 시 IllegalStateException → 400)
public interface TossStatisticsUseCase {
    // GET /api/v1/candles — 캔들차트
    List<TossCandle> getCandles(UUID accountId, UUID requesterId, Ticker ticker, String interval, LocalDate from, LocalDate to);
    // GET /api/v1/stocks — 종목 기본 정보
    TossStockInfo getStockInfo(UUID accountId, UUID requesterId, Ticker ticker);
    // GET /api/v1/exchange-rate — 환율 (USD/KRW)
    TossExchangeRate getExchangeRate(UUID accountId, UUID requesterId);
    // GET /api/v1/market-calendar/US — 해외 장 운영 정보
    List<TossMarketSession> getMarketCalendar(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);
    // GET /api/v1/accounts — 계좌 목록
    List<TossAccountInfo> getAccountList(UUID accountId, UUID requesterId);
}
