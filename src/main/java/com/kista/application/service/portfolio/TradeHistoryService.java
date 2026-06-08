package com.kista.application.service.portfolio;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.port.in.GetTradeHistoryUseCase;
import com.kista.domain.port.out.OrderPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
class TradeHistoryService implements GetTradeHistoryUseCase {

    private final OrderPort orderPort;

    @Override
    public List<Order> getHistory(LocalDate from, LocalDate to, Ticker ticker) {
        return orderPort.findBy(from, to, ticker);
    }
}
