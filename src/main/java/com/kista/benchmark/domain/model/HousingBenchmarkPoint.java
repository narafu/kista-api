package com.kista.benchmark.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record HousingBenchmarkPoint(
        LocalDate baseDate,
        BigDecimal investmentIndexUsd,
        BigDecimal benchmarkIndex,
        BigDecimal investmentPeriodReturn,
        BigDecimal benchmarkPeriodReturn
) {}
