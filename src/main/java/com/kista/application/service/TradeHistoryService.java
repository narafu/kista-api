package com.kista.application.service;

import com.kista.domain.model.strategy.Ticker;
import com.kista.domain.model.order.TradeHistory;
import com.kista.domain.port.in.GetTradeHistoryUseCase;
import com.kista.domain.port.out.TradeHistoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TradeHistoryService implements GetTradeHistoryUseCase {

    private final TradeHistoryPort tradeHistoryPort;

    @Override
    public List<TradeHistory> getHistory(LocalDate from, LocalDate to, Ticker ticker) {
        return tradeHistoryPort.findBy(from, to, ticker);
    }
}
