package com.kista.domain.port.in;

import com.kista.domain.model.kis.DailyTransactionResult;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.kis.PresentBalanceResult;
import com.kista.domain.model.strategy.CycleHistoryPage;
import com.kista.domain.model.strategy.CyclePositionHistoryEntry;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossAccountInfo;
import com.kista.domain.model.toss.TossCandle;
import com.kista.domain.model.toss.TossExchangeRate;
import com.kista.domain.model.toss.TossMarketSession;
import com.kista.domain.model.toss.TossSellableQuantity;
import com.kista.domain.model.toss.TossStockInfo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// KIS/Toss 통계 + trading_cycle_history 조회 통합 인터페이스
public interface AccountStatisticsUseCase {
    List<Execution> getExecutions(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);
    PresentBalanceResult getPresentBalance(UUID accountId, UUID requesterId);
    List<MarginItem> getMargin(UUID accountId, UUID requesterId);
    DailyTransactionResult getDailyTransactions(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);
    Map<Ticker, BigDecimal> getPrices(UUID accountId, UUID requesterId, List<Ticker> tickers);
    CycleHistoryPage getByAccount(UUID accountId, UUID requesterId, LocalDate from, LocalDate to, Instant cursor, int size);
    CycleHistoryPage getByStrategy(UUID strategyId, UUID requesterId, LocalDate from, LocalDate to, Instant cursor, int size);
    // 계좌 기준 스냅샷 조회 (차트용 — DB 기반, KIS API 미사용)
    List<CyclePositionHistoryEntry> getSnapshotsByAccount(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);

    // ── Toss 전용 ──────────────────────────────────────────────────────────────
    // GET /api/v1/candles — 캔들차트
    List<TossCandle> getTossCandles(UUID accountId, UUID requesterId, Ticker ticker, String interval, LocalDate from, LocalDate to);
    // GET /api/v1/stocks — 종목 기본 정보
    TossStockInfo getTossStockInfo(UUID accountId, UUID requesterId, Ticker ticker);
    // GET /api/v1/exchange-rate — 환율 (USD/KRW)
    TossExchangeRate getTossExchangeRate(UUID accountId, UUID requesterId);
    // GET /api/v1/market-calendar/US — 해외 장 운영 정보
    List<TossMarketSession> getTossMarketCalendar(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);
    // GET /api/v1/accounts — 계좌 목록
    List<TossAccountInfo> getTossAccountList(UUID accountId, UUID requesterId);
    // KIS: CTRP6504R 잔고수량 / Toss: GET /api/v1/sellable-quantity — 판매 가능 수량
    TossSellableQuantity getSellableQuantity(UUID accountId, UUID requesterId, Ticker ticker);
}
