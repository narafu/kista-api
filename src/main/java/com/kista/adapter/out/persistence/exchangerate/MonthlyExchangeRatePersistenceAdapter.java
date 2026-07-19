package com.kista.adapter.out.persistence.exchangerate;

import com.kista.domain.model.market.MonthlyExchangeRate;
import com.kista.domain.port.out.MonthlyExchangeRatePort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

@Component
@RequiredArgsConstructor
public class MonthlyExchangeRatePersistenceAdapter implements MonthlyExchangeRatePort {

    private final MonthlyExchangeRateJpaRepository repository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void upsert(MonthlyExchangeRate exchangeRate) {
        jdbcTemplate.update("""
                INSERT INTO monthly_exchange_rates (
                    source,
                    base_currency,
                    quote_currency,
                    base_month,
                    exchange_rate_date,
                    mid_rate,
                    fetched_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source, base_currency, quote_currency, base_month) DO UPDATE
                   SET exchange_rate_date = EXCLUDED.exchange_rate_date,
                       mid_rate = EXCLUDED.mid_rate,
                       fetched_at = EXCLUDED.fetched_at,
                       updated_at = now()
                """,
                exchangeRate.source(),
                exchangeRate.baseCurrency(),
                exchangeRate.quoteCurrency(),
                exchangeRate.baseMonth(),
                exchangeRate.exchangeRateDate(),
                exchangeRate.midRate(),
                Timestamp.from(exchangeRate.fetchedAt())
        );
    }

    @Override
    public List<MonthlyExchangeRate> findByCurrenciesAndBaseMonthBetween(
            String baseCurrency, String quoteCurrency, LocalDate from, LocalDate to) {
        return repository.findByBaseCurrencyAndQuoteCurrencyAndBaseMonthBetweenOrderByBaseMonthAsc(
                        baseCurrency, quoteCurrency, from, to)
                .stream()
                .map(MonthlyExchangeRateEntity::toDomain)
                .toList();
    }
}
