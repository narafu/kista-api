package com.kista.domain.model.privacy;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy.Ticker;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record FidaOrderCommand(
        @NotNull LocalDate tradeDate,
        @NotNull Ticker ticker,
        @NotNull @Positive BigDecimal currentCycleStart,
        @NotNull BigDecimal currentCycleRealizedPnl,
        @Nullable BigDecimal avgPrice,
        @PositiveOrZero int holdings,
        List<Order> orders
) {
    // quantity=null은 "남은 전부 매도"를 의미 — SELL 방향에서만 허용
    @AssertTrue(message = "BUY 주문의 quantity는 null일 수 없습니다")
    public boolean isBuyQuantityValid() {
        return orders == null || orders.stream()
                .filter(o -> o.direction() == Order.OrderDirection.BUY)
                .allMatch(o -> o.quantity() != null);
    }
}
