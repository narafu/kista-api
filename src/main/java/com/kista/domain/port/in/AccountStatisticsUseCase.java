package com.kista.domain.port.in;

import com.kista.domain.model.kis.DailyTransactionResult;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.kis.PresentBalanceResult;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.CycleHistoryPage;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.strategy.StrategySeedPreview;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// KIS/Toss 공통 통계 + trading_cycle_history 조회 인터페이스
public interface AccountStatisticsUseCase {
    PresentBalanceResult getPresentBalance(UUID accountId, UUID requesterId);
    List<MarginItem> getMargin(UUID accountId, UUID requesterId);
    DailyTransactionResult getDailyTransactions(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);
    Map<Ticker, BigDecimal> getPrices(UUID accountId, UUID requesterId, List<Ticker> tickers);
    CycleHistoryPage getByAccount(UUID accountId, UUID requesterId, LocalDate from, LocalDate to, Instant cursor, int size);
    CycleHistoryPage getByStrategy(UUID strategyId, UUID requesterId, LocalDate from, LocalDate to, Instant cursor, int size);
    // 전략 등록/수정 폼용 최소시드·기준가 미리보기
    StrategySeedPreview strategySeedPreview(UUID accountId, UUID requesterId,
            Strategy.Type type, Strategy.Ticker ticker, int divisionCount);

    // 전략 기준 기간 내 주문 내역 조회 (사용자 전략 상세 화면용)
    List<Order> getOrdersByStrategy(UUID strategyId, UUID requesterId, LocalDate from, LocalDate to);
}
