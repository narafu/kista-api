package com.kista.stats.application.usecase;

import com.kista.broker.domain.model.DailyTransactionResult;
import com.kista.broker.domain.model.MarginItem;
import com.kista.broker.domain.model.PresentBalanceResult;
import com.kista.trading.domain.model.CycleHistoryPage;
import com.kista.sharedkernel.StrategyTicker;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// KIS/Toss 공통 통계 + 계좌 스코프 사이클 이력 조회 인터페이스
// (전략 스코프 이력·주문·시드 미리보기는 trading StrategyUseCase 소유)
public interface AccountStatisticsUseCase {
    PresentBalanceResult getPresentBalance(UUID accountId, UUID requesterId);
    List<MarginItem> getMargin(UUID accountId, UUID requesterId);
    DailyTransactionResult getDailyTransactions(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);
    // 유저 스코프 일별 거래내역 조회 (대시보드 위젯용 — 보유 계좌 전체 합산, 1회 조회)
    DailyTransactionResult getDailyTransactionsForUser(UUID requesterId, LocalDate from, LocalDate to);
    Map<StrategyTicker, BigDecimal> getPrices(UUID accountId, UUID requesterId, List<StrategyTicker> tickers);
    CycleHistoryPage getByAccount(UUID accountId, UUID requesterId, LocalDate from, LocalDate to, Instant cursor, int size);
}
