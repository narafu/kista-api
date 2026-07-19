package com.kista.domain.model.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record MonthlyExchangeRate(
        UUID id,
        String source,
        String baseCurrency,
        String quoteCurrency,
        LocalDate baseMonth,
        LocalDate exchangeRateDate,
        BigDecimal midRate,
        Instant fetchedAt
) {
}
