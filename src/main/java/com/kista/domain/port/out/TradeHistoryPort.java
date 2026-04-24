package com.kista.domain.port.out;

import com.kista.domain.model.TradeHistory;

import java.time.LocalDate;
import java.util.List;

public interface TradeHistoryPort {
    void save(TradeHistory h);
    List<TradeHistory> findBy(LocalDate from, LocalDate to, String symbol);
}
