package com.kista.domain.model.stats;

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
