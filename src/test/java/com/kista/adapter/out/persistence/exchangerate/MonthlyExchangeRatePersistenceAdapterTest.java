package com.kista.adapter.out.persistence.exchangerate;

import com.kista.domain.model.market.MonthlyExchangeRate;
import com.kista.support.DataJpaTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Import(MonthlyExchangeRatePersistenceAdapter.class)
@Execution(ExecutionMode.SAME_THREAD)
class MonthlyExchangeRatePersistenceAdapterTest extends DataJpaTestBase {

    @Autowired MonthlyExchangeRatePersistenceAdapter adapter;

    @Test
    void upsert_updatesNaturalKeyAndFindsOnlyRequestedCurrencyPairByBaseMonthAscending() {
        MonthlyExchangeRate june = rate("USD", "KRW", LocalDate.of(2026, 6, 1), "1365.200000");
        MonthlyExchangeRate may = rate("USD", "KRW", LocalDate.of(2026, 5, 1), "1350.100000");
        MonthlyExchangeRate euro = rate("EUR", "KRW", LocalDate.of(2026, 6, 1), "1490.300000");

        adapter.upsert(june);
        adapter.upsert(may);
        adapter.upsert(euro);
        adapter.upsert(rate("USD", "KRW", LocalDate.of(2026, 6, 1), "1366.400000"));

        List<MonthlyExchangeRate> result = adapter.findByCurrenciesAndBaseMonthBetween(
                "USD", "KRW", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 1));

        assertThat(result).extracting(MonthlyExchangeRate::baseMonth)
                .containsExactly(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 1));
        assertThat(result).extracting(MonthlyExchangeRate::midRate)
                .containsExactly(new BigDecimal("1350.100000"), new BigDecimal("1366.400000"));
    }

    private static MonthlyExchangeRate rate(String baseCurrency, String quoteCurrency, LocalDate baseMonth, String midRate) {
        return new MonthlyExchangeRate(
                null,
                "TOSS_INVEST",
                baseCurrency,
                quoteCurrency,
                baseMonth,
                baseMonth.withDayOfMonth(baseMonth.lengthOfMonth()),
                new BigDecimal(midRate),
                Instant.parse("2026-06-30T07:00:00Z")
        );
    }
}
