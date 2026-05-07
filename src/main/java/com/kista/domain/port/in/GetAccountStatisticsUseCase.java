package com.kista.domain.port.in;

import com.kista.domain.model.Execution;
import com.kista.domain.model.PeriodProfitResult;
import com.kista.domain.model.PresentBalanceResult;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface GetAccountStatisticsUseCase {
    PeriodProfitResult getPeriodProfit(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);
    List<Execution> getTrades(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);
    PresentBalanceResult getPresentBalance(UUID accountId, UUID requesterId);
}
