package com.kista.privacy.domain.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.kista.sharedkernel.StrategyTicker;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record FidaOrderCommand(
        @NotNull @JsonAlias("tradeDate") LocalDate releaseDate, // FIDA 발행일 원본 (KST) — 거래일 아님
        @NotNull StrategyTicker ticker,
        @NotNull @Positive BigDecimal currentCycleStart,
        @NotNull BigDecimal currentCycleRealizedPnl,
        @Nullable BigDecimal avgPrice,
        @PositiveOrZero int holdings,
        List<FidaPlannedOrder> orders
) {
    // quantity=null은 "남은 전부 매도"를 의미 — SELL 방향에서만 허용
    @AssertTrue(message = "BUY 주문의 quantity는 null일 수 없습니다")
    public boolean isBuyQuantityValid() {
        return orders == null || orders.stream()
                .filter(o -> o.direction() == PrivacyOrderDirection.BUY)
                .allMatch(o -> o.quantity() != null);
    }
}
