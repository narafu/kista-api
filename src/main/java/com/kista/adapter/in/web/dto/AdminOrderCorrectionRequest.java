package com.kista.adapter.in.web.dto;

import com.kista.domain.model.admin.AdminOrderCorrectionCommand;
import com.kista.domain.model.order.Order;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// 관리자 주문 보정 요청 DTO
public record AdminOrderCorrectionRequest(
        @NotNull UUID userId,
        @NotNull UUID accountId,
        @NotNull UUID strategyId,
        @NotNull UUID orderId,
        @NotNull String mode,
        @NotNull LocalDate tradeDateKst,
        String direction,
        Integer quantity,
        BigDecimal price,
        String memo
) {
    public AdminOrderCorrectionCommand toCommand() {
        return new AdminOrderCorrectionCommand(
                userId,
                accountId,
                strategyId,
                orderId,
                AdminOrderCorrectionCommand.Mode.valueOf(mode),
                tradeDateKst,
                direction != null ? Order.OrderDirection.valueOf(direction) : null,
                quantity,
                price,
                memo
        );
    }
}
