package com.kista.domain.port.in;

import com.kista.domain.model.DailyTransactionResult;
import com.kista.domain.model.Execution;
import com.kista.domain.model.MarginItem;
import com.kista.domain.model.PeriodProfitResult;
import com.kista.domain.model.PresentBalanceResult;
import com.kista.domain.model.ReservationOrder;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface GetAccountStatisticsUseCase {
    PeriodProfitResult getPeriodProfit(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);
    List<Execution> getTrades(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);
    PresentBalanceResult getPresentBalance(UUID accountId, UUID requesterId);

    // 해외증거금 통화별조회 (USD·KRW)
    List<MarginItem> getMargin(UUID accountId, UUID requesterId);

    // 일별거래내역 조회
    DailyTransactionResult getDailyTransactions(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);

    // 예약주문 목록 조회
    List<ReservationOrder> getReservationOrders(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);
}
