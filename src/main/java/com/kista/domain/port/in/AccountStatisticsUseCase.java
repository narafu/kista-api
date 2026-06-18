package com.kista.domain.port.in;

import com.kista.domain.model.kis.DailyTransactionResult;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.kis.PeriodProfitResult;
import com.kista.domain.model.kis.PresentBalanceResult;
import com.kista.domain.model.strategy.CycleHistoryPage;
import com.kista.domain.model.strategy.CyclePositionHistoryEntry;
import com.kista.domain.model.strategy.Strategy.Ticker;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// KIS 통계 + trading_cycle_history 조회 통합 인터페이스
public interface AccountStatisticsUseCase {
    PeriodProfitResult getPeriodProfit(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);
    List<Execution> getExecutions(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);
    PresentBalanceResult getPresentBalance(UUID accountId, UUID requesterId);
    List<MarginItem> getMargin(UUID accountId, UUID requesterId);
    DailyTransactionResult getDailyTransactions(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);
    Map<Ticker, BigDecimal> getPrices(UUID accountId, UUID requesterId, List<Ticker> tickers);
    CycleHistoryPage getByAccount(UUID accountId, UUID requesterId, LocalDate from, LocalDate to, Instant cursor, int size);
    CycleHistoryPage getByStrategy(UUID strategyId, UUID requesterId, LocalDate from, LocalDate to, Instant cursor, int size);
    // 계좌 기준 스냅샷 조회 (차트용 — DB 기반, KIS API 미사용)
    List<CyclePositionHistoryEntry> getSnapshotsByAccount(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);
}
