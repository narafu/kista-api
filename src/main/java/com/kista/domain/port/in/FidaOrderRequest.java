package com.kista.domain.port.in;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Ticker;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record FidaOrderRequest(
        @NotNull LocalDate tradeDate,
        @NotNull Ticker ticker,
        @NotNull @Positive BigDecimal currentCycleStart,
        @NotNull BigDecimal currentCycleRealizedPnl,
        @Nullable BigDecimal avgPrice,
        @PositiveOrZero int holdings,
        List<Order> orders
) {}
