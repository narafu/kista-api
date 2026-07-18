package com.kista.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.kista.domain.model.admin.AdminReorderCommand;
import com.kista.domain.model.order.Order;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// 관리자 재주문 요청 DTO
public record AdminReorderRequest(
        @Schema(description = "대상 사용자 ID")
        @NotNull UUID userId,
        @Schema(description = "대상 계좌 ID")
        @NotNull UUID accountId,
        @Schema(description = "대상 전략 ID")
        @NotNull UUID strategyId,
        @Schema(description = "재주문 대상 원본 주문 ID")
        @NotNull UUID orderId,
        @Schema(description = "재주문 접수 시점", example = "AT_OPEN")
        @NotNull String timing,        // AT_OPEN / AT_CLOSE / IMMEDIATE
        @Schema(description = "거래일 (KST)")
        @NotNull @JsonAlias("tradeDateKst") LocalDate tradeDate,
        @Schema(description = "매매 방향 (생략 시 원본 주문 값 사용)", example = "BUY")
        String direction,
        @Schema(description = "주문 수량")
        @NotNull Integer quantity,
        @Schema(description = "주문 가격")
        @NotNull BigDecimal price,
        @Schema(description = "메모 (선택)")
        String memo
) {
    public AdminReorderCommand toCommand() {
        return new AdminReorderCommand(
                userId,
                accountId,
                strategyId,
                orderId,
                Order.OrderTiming.valueOf(timing),
                tradeDate,
                direction != null ? Order.OrderDirection.valueOf(direction) : null,
                quantity,
                price,
                memo
        );
    }
}
