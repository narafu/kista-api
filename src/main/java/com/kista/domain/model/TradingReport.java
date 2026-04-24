package com.kista.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record TradingReport(
        LocalDate date,
        TradingVariables vars,
        List<Order> mainOrders,
        List<Order> correctionOrders,
        BigDecimal totalBoughtUsd,
        BigDecimal totalSoldUsd
) {}
