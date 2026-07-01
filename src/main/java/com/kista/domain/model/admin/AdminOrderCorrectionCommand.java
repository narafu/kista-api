package com.kista.domain.model.admin;

import com.kista.domain.model.order.Order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record AdminOrderCorrectionCommand(
        UUID userId,
        UUID accountId,
        UUID strategyId,
        UUID orderId,
        Mode mode,
        LocalDate tradeDateKst,
        Order.OrderDirection direction,
        Integer quantity,
        BigDecimal price,
        String memo
) {
    public enum Mode {
        PLANNED_EDIT,
        PLACED_REPLACE,
        FILLED_CORRECTION
    }
}
