package com.kista.adapter.in.web.dto;

import com.kista.domain.model.Order;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record FidaOrderRequestDto(
        @Schema(description = "종목 코드", example = "SOXL")
        @NotBlank String symbol,
        @Schema(description = "매매 방향 (BUY 또는 SELL)", example = "BUY")
        @NotNull Order.OrderDirection direction,
        @Schema(description = "주문 수량", example = "5")
        @Positive int qty,
        @Schema(description = "주문 단가 (USD)", example = "85.50")
        @NotNull @Positive BigDecimal price
) {}
