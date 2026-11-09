package com.kista.api.benchmark.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BenchmarkPoint(
        LocalDate baseDate,
        BigDecimal investmentIndex,
        BigDecimal benchmarkIndex,
        BigDecimal investmentPeriodReturn,
        BigDecimal benchmarkPeriodReturn
) {}
