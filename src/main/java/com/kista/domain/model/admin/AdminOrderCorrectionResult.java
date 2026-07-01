package com.kista.domain.model.admin;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record AdminOrderCorrectionResult(
        UUID userId,
        UUID accountId,
        UUID strategyId,
        UUID orderId,
        AdminOrderCorrectionCommand.Mode mode,
        Order.OrderStatus originalStatus,
        Order.OrderStatus resultingStatus,
        String replacementExternalOrderId,
        int finalHoldings,
        BigDecimal finalAvgPrice,
        BigDecimal finalUsdDeposit,
        Strategy.Status strategyStatus,
        boolean cycleEnded,
        LocalDate cycleEndDate
) {}
