package com.kista.contract.privacy;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.StrategyTicker;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// FIDA 주문 수신 — POST /api/internal/fida-orders body. 외부 FIDA 프로젝트도 호출하므로 JSON 필드명·shape을 바꾸지 말 것
public record FidaOrderRequest(
        @NotNull @JsonAlias("tradeDate") LocalDate releaseDate, // FIDA 발행일 원본 (KST) — 거래일 아님
        @NotNull StrategyTicker ticker,                         // 거래 종목
        @NotNull @Positive BigDecimal currentCycleStart,        // 기준가
        @NotNull BigDecimal currentCycleRealizedPnl,            // 사이클 실현 수익($)
        BigDecimal avgPrice,                                    // 평단가 (nullable)
        @PositiveOrZero int holdings,                           // 보유 수량
        List<PlannedOrder> orders                               // 계획 주문
) {
    // quantity=null은 "남은 전부 매도"를 의미 — SELL 방향에서만 허용
    @JsonIgnore
    @AssertTrue(message = "BUY 주문의 quantity는 null일 수 없습니다")
    public boolean isBuyQuantityValid() {
        return orders == null || orders.stream()
                .filter(o -> o.direction() == OrderDirection.BUY)
                .allMatch(o -> o.quantity() != null);
    }

    // FIDA 계획 주문 1건
    public record PlannedOrder(
            OrderDirection direction, // 매수/매도
            OrderType orderType,      // LOC / MOC / LIMIT
            Integer quantity,         // 주문 수량 (nullable — SELL "잔량 전부" 의미)
            BigDecimal price          // 주문 가격
    ) {}
}
