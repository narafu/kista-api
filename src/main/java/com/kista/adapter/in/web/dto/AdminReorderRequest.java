package com.kista.adapter.in.web.dto;

import com.kista.domain.model.admin.AdminReorderCommand;
import com.kista.domain.model.order.Order;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// 관리자 재주문 요청 DTO
public record AdminReorderRequest(
        @NotNull UUID userId,
        @NotNull UUID accountId,
        @NotNull UUID strategyId,
        @NotNull UUID orderId,
        @NotNull String timing,        // AT_OPEN / AT_CLOSE / IMMEDIATE
        @NotNull LocalDate tradeDateKst,
        String direction,
        @NotNull Integer quantity,
        @NotNull BigDecimal price,
        String memo
) {
    public AdminReorderCommand toCommand() {
        return new AdminReorderCommand(
                userId,
                accountId,
                strategyId,
                orderId,
                Order.OrderTiming.valueOf(timing),
                tradeDateKst,
                direction != null ? Order.OrderDirection.valueOf(direction) : null,
                quantity,
                price,
                memo
        );
    }
}
