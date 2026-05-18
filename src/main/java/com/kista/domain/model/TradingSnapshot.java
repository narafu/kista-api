package com.kista.domain.model;

import java.math.BigDecimal;

public record TradingSnapshot(
        int quantity,
        BigDecimal averagePrice,
        BigDecimal priceOffsetRate,
        BigDecimal targetPrice
) {}
