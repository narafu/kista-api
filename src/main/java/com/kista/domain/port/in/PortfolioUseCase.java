package com.kista.domain.port.in;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.tradingcycle.AccountCycleHistoryEntry;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;

import java.time.LocalDate;
import java.util.List;

public interface PortfolioUseCase {
    AccountCycleHistoryEntry getCurrent();
    List<AccountCycleHistoryEntry> getSnapshots(LocalDate from, LocalDate to);
    List<Order> getHistory(LocalDate from, LocalDate to, Ticker ticker);
}
