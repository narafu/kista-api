package com.kista.benchmark.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

public record CurrentExchangeRate(
        BigDecimal midRate,
        Instant fetchedAt,
        String source
) {}
