package com.kista.shared.contract.privacy;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

// FIDA 주문 수신 — POST /api/internal/fida-orders body. 외부 FIDA 프로젝트도 호출하므로 JSON 필드명·shape을 바꾸지 말 것
public record FidaOrderRequest(
        @NotNull(message = "발행일은 필수입니다.") @JsonAlias("tradeDate") LocalDate releaseDate, // FIDA 발행일 원본 (KST) — 거래일 아님
        @NotNull(message = "종목은 필수입니다.") StrategyTicker ticker,                         // 거래 종목
        @NotNull(message = "기준가는 필수입니다.") @Positive(message = "기준가는 0보다 커야 합니다.") BigDecimal currentCycleStart,        // 기준가
        @NotNull(message = "사이클 실현 수익은 필수입니다.") BigDecimal currentCycleRealizedPnl,            // 사이클 실현 수익($)
        @Nullable BigDecimal avgPrice,                                    // 평단가 (nullable)
        @PositiveOrZero(message = "보유 수량은 0 이상이어야 합니다.") int holdings,                           // 보유 수량
        @NotNull(message = "계획 주문은 필수입니다.") List<@NotNull(message = "계획 주문 항목은 비워 둘 수 없습니다.") @Valid PlannedOrder> orders      // 계획 주문 — 누락·null 원소면 400(빈 목록은 허용)
) {
    // quantity=null은 "남은 전부 매도"를 의미 — SELL 방향에서만 허용
    @JsonIgnore
    @AssertTrue(message = "매수 주문은 수량이 필요합니다.")
    public boolean isBuyQuantityValid() {
        return orders == null || orders.stream()
                .filter(o -> o != null && o.direction() == OrderDirection.BUY) // null 원소는 NotNull 제약이 400으로 거른다 — 여기서 NPE로 500이 되지 않게
                .allMatch(o -> o.quantity() != null);
    }

    // FIDA 계획 주문 1건
    public record PlannedOrder(
            @NotNull(message = "매매 방향은 필수입니다.") OrderDirection direction,    // 매수/매도
            @NotNull(message = "주문 유형은 필수입니다.") OrderType orderType,      // LOC / MOC / LIMIT
            @Nullable Integer quantity,         // 주문 수량 (nullable — SELL "잔량 전부" 의미)
            @NotNull(message = "주문 가격은 필수입니다.") BigDecimal price          // 주문 가격
    ) {}
}
