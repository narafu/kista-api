package com.kista.domain.port.in;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.CyclePositionHistoryEntry;
import com.kista.domain.model.strategy.Strategy.Ticker;

import java.time.LocalDate;
import java.util.List;

public interface PortfolioUseCase {
    CyclePositionHistoryEntry getCurrent();
    List<CyclePositionHistoryEntry> getSnapshots(LocalDate from, LocalDate to);
    List<Order> getHistory(LocalDate from, LocalDate to, Ticker ticker);
}
