package com.kista.domain.model.stats;

import java.math.BigDecimal;
import java.time.Instant;

public record CurrentExchangeRate(
        BigDecimal midRate,
        Instant fetchedAt,
        String source
) {}
