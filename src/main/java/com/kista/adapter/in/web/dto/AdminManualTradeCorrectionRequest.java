package com.kista.adapter.in.web.dto;

import com.kista.domain.model.admin.AdminManualTradeCorrectionCommand;
import com.kista.domain.model.order.Order;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// 관리자 수동 체결 보정 요청 DTO
public record AdminManualTradeCorrectionRequest(
        @NotNull UUID userId,
        @NotNull UUID accountId,
        @NotNull UUID strategyId,
        @NotEmpty List<@Valid FillRequest> fills
) {
    public AdminManualTradeCorrectionCommand toCommand() {
        return new AdminManualTradeCorrectionCommand(
                userId, accountId, strategyId,
                fills.stream().map(FillRequest::toCommand).toList()
        );
    }

    // 개별 체결 명세
    public record FillRequest(
            @NotNull LocalDate tradeDateKst,
            @NotNull String direction,
            @NotNull @Positive Integer quantity,
            @NotNull @Positive BigDecimal price,
            String externalOrderId,
            String memo
    ) {
        public AdminManualTradeCorrectionCommand.Fill toCommand() {
            return new AdminManualTradeCorrectionCommand.Fill(
                    tradeDateKst,
                    Order.OrderDirection.valueOf(direction),
                    quantity,
                    price,
                    externalOrderId,
                    memo
            );
        }
    }
}
