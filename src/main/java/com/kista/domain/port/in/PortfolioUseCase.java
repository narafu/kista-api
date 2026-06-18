package com.kista.domain.port.in;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.CyclePositionHistoryEntry;
import com.kista.domain.model.strategy.Strategy.Ticker;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PortfolioUseCase {
    CyclePositionHistoryEntry getCurrent(UUID userId);
    List<CyclePositionHistoryEntry> getSnapshots(UUID userId, LocalDate from, LocalDate to);
    List<Order> getHistory(UUID userId, LocalDate from, LocalDate to, Ticker ticker);
}
