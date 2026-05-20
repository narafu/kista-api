package com.kista.domain.model.strategy;

import java.math.BigDecimal;

public record TradingSnapshot(
        int holdings,
        BigDecimal averagePrice,
        BigDecimal priceOffsetRate,
        BigDecimal targetPrice
) {}
