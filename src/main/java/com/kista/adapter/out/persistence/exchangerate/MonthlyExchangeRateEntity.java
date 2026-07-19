package com.kista.adapter.out.persistence.exchangerate;

import com.kista.adapter.out.persistence.BaseAuditEntity;
import com.kista.domain.model.market.MonthlyExchangeRate;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
    name = "monthly_exchange_rates",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_monthly_exchange_rates_source_pair_month",
        columnNames = {"source", "base_currency", "quote_currency", "base_month"}
    )
)
@Getter
@NoArgsConstructor
class MonthlyExchangeRateEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "source", nullable = false, length = 20)
    private String source;

    @Column(name = "base_currency", nullable = false, length = 3)
    private String baseCurrency;

    @Column(name = "quote_currency", nullable = false, length = 3)
    private String quoteCurrency;

    @Column(name = "base_month", nullable = false)
    private LocalDate baseMonth;

    @Column(name = "exchange_rate_date", nullable = false)
    private LocalDate exchangeRateDate;

    @Column(name = "mid_rate", nullable = false, precision = 18, scale = 6)
    private BigDecimal midRate;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    MonthlyExchangeRate toDomain() {
        return new MonthlyExchangeRate(
                id,
                source,
                baseCurrency,
                quoteCurrency,
                baseMonth,
                exchangeRateDate,
                midRate,
                fetchedAt
        );
    }
}
