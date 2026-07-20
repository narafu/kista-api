package com.kista.domain.model.stats;

import java.math.BigDecimal;
import java.time.LocalDate;

public record HousingBenchmarkPoint(
        LocalDate baseDate,
        BigDecimal investmentIndexUsd,
        BigDecimal benchmarkIndex,
        BigDecimal investmentPeriodReturn,
        BigDecimal benchmarkPeriodReturn
) {}
