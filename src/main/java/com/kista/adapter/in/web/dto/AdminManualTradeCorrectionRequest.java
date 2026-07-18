package com.kista.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.kista.domain.model.admin.AdminManualTradeCorrectionCommand;
import com.kista.domain.model.order.Order;
import io.swagger.v3.oas.annotations.media.Schema;
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
        @Schema(description = "대상 사용자 ID")
        @NotNull UUID userId,
        @Schema(description = "대상 계좌 ID")
        @NotNull UUID accountId,
        @Schema(description = "대상 전략 ID")
        @NotNull UUID strategyId,
        @Schema(description = "반영할 체결 명세 목록 (배열 순서대로 반영)")
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
            @Schema(description = "체결 거래일 (KST)")
            @NotNull @JsonAlias("tradeDateKst") LocalDate tradeDate,
            @Schema(description = "매매 방향", example = "BUY")
            @NotNull String direction,
            @Schema(description = "체결 수량")
            @NotNull @Positive Integer quantity,
            @Schema(description = "체결 가격")
            @NotNull @Positive BigDecimal price,
            @Schema(description = "브로커 측 원본 주문 ID (선택)")
            String externalOrderId,
            @Schema(description = "메모 (선택)")
            String memo
    ) {
        public AdminManualTradeCorrectionCommand.Fill toCommand() {
            return new AdminManualTradeCorrectionCommand.Fill(
                    tradeDate,
                    Order.OrderDirection.valueOf(direction),
                    quantity,
                    price,
                    externalOrderId,
                    memo
            );
        }
    }
}
