package com.kista.benchmark.domain.model;

import java.math.BigDecimal;

public record PerformanceComparisonSummary(
        BigDecimal investmentCumulativeReturn,
        BigDecimal benchmarkCumulativeReturn,
        BigDecimal excessReturn,
        BigDecimal investmentAnnualizedReturn,
        BigDecimal benchmarkAnnualizedReturn,
        BigDecimal investmentMaxDrawdown,
        BigDecimal benchmarkMaxDrawdown
) {}
