package com.kista.domain.port.in;

import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.model.order.TradeHistory;

import java.time.LocalDate;
import java.util.List;

public interface GetTradeHistoryUseCase {
    List<TradeHistory> getHistory(LocalDate from, LocalDate to, Ticker ticker);
}
