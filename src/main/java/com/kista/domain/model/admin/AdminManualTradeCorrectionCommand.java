package com.kista.domain.model.admin;

import com.kista.domain.model.order.Order;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// 관리자 수동 체결 보정 입력 — fills 배열 순서대로 반영
public record AdminManualTradeCorrectionCommand(
        @NotNull UUID userId,
        @NotNull UUID accountId,
        @NotNull UUID strategyId,
        @NotEmpty List<@Valid Fill> fills
) {
    public record Fill(
            @NotNull LocalDate tradeDateKst,
            @NotNull Order.OrderDirection direction,
            @Positive int quantity,
            @NotNull @Positive BigDecimal price,
            String externalOrderId,
            String memo
    ) {
    }
}
