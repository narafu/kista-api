package com.kista.domain.port.in;

import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.CyclePositionHistoryEntry;
import com.kista.domain.model.strategy.Strategy.Ticker;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PortfolioUseCase {
    CyclePositionHistoryEntry getCurrent(UUID userId);
    List<Order> getHistory(UUID userId, LocalDate from, LocalDate to, Ticker ticker);
}
