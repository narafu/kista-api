package com.kista.stats.application.usecase;

import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.CyclePositionHistoryEntry;
import com.kista.sharedkernel.StrategyTicker;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PortfolioUseCase {
    CyclePositionHistoryEntry getCurrent(UUID userId);
    List<Order> getHistory(UUID userId, LocalDate from, LocalDate to, StrategyTicker ticker);
}
