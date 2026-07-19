package com.kista.domain.port.out;

import com.kista.domain.model.market.MonthlyExchangeRate;

import java.time.LocalDate;
import java.util.List;

public interface MonthlyExchangeRatePort {
    void upsert(MonthlyExchangeRate exchangeRate);

    List<MonthlyExchangeRate> findByCurrenciesAndBaseMonthBetween(
            String baseCurrency, String quoteCurrency, LocalDate from, LocalDate to);
}
