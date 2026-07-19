package com.kista.adapter.out.persistence.exchangerate;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface MonthlyExchangeRateJpaRepository extends JpaRepository<MonthlyExchangeRateEntity, UUID> {
    List<MonthlyExchangeRateEntity> findByBaseCurrencyAndQuoteCurrencyAndBaseMonthBetweenOrderByBaseMonthAsc(
            String baseCurrency, String quoteCurrency, LocalDate from, LocalDate to);
}
