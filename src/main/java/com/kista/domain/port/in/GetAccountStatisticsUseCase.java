package com.kista.domain.port.in;

import com.kista.domain.model.kis.DailyTransactionResult;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.kis.PeriodProfitResult;
import com.kista.domain.model.kis.PresentBalanceResult;
import com.kista.domain.model.tradingcycle.AccountCycleHistoryEntry;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface GetAccountStatisticsUseCase {
    PeriodProfitResult getPeriodProfit(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);
    List<Execution> getTrades(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);
    PresentBalanceResult getPresentBalance(UUID accountId, UUID requesterId);

    // 해외증거금 통화별조회 (USD·KRW)
    List<MarginItem> getMargin(UUID accountId, UUID requesterId);

    // 일별거래내역 조회
    DailyTransactionResult getDailyTransactions(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);

    // 복수 종목 현재가 조회 (KIS HHDFS76410000)
    Map<Ticker, BigDecimal> getPrices(UUID accountId, UUID requesterId, List<Ticker> tickers);

    // 계좌 기준 trading_cycle_history 조회 (DB, KIS API 미사용)
    List<AccountCycleHistoryEntry> getCycleHistory(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);

    // 전략(사이클) 기준 trading_cycle_history 조회 (DB, KIS API 미사용)
    List<AccountCycleHistoryEntry> getStrategyCycleHistory(UUID strategyId, UUID requesterId, LocalDate from, LocalDate to);
}
