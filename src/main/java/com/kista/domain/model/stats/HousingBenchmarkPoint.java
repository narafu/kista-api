package com.kista.domain.model.stats;

import java.math.BigDecimal;
import java.time.LocalDate;

public record HousingBenchmarkPoint(
        LocalDate baseMonth,
        BigDecimal investmentIndexUsd,
        BigDecimal benchmarkIndex,
        BigDecimal investmentMonthlyReturn,
        BigDecimal benchmarkMonthlyReturn
) {}
