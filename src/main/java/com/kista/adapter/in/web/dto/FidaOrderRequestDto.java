package com.kista.adapter.in.web.dto;

import com.kista.domain.model.Order;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record FidaOrderRequestDto(
        @NotBlank String symbol,
        @NotNull Order.OrderDirection direction,
        @Positive int qty,
        @NotNull @Positive BigDecimal price
) {}
