package com.kista.trading.domain.model;

import java.math.BigDecimal;

public record TradingSnapshot(
        int holdings,
        BigDecimal averagePrice,
        BigDecimal priceOffsetRate,
        BigDecimal targetPrice
) {}
