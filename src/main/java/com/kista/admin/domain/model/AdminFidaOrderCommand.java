package com.kista.admin.domain.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.StrategyTicker;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// privacy.domain.model.FidaOrderCommand의 admin own-type — 관리자 PRIVACY 기준 매매표 수동 등록
// (AdminPrivacyTradeController.createBase) 요청 바디로 쓰며, PrivacyQueryHttpAdapter가 그대로
// 기존 POST /api/internal/fida-orders(FidaOrderController)에 전달한다. Gradle 컴파일 경계
// (:trading-core→:api 역방향 의존 금지)로 원본 타입을 import할 수 없어 1:1 복제.
public record AdminFidaOrderCommand(
        @NotNull @JsonAlias("tradeDate") LocalDate releaseDate, // FIDA 발행일 원본 (KST) — 거래일 아님
        @NotNull StrategyTicker ticker,
        @NotNull @Positive BigDecimal currentCycleStart,
        @NotNull BigDecimal currentCycleRealizedPnl,
        @Nullable BigDecimal avgPrice,
        @PositiveOrZero int holdings,
        List<PlannedOrder> orders
) {
    // quantity=null은 "남은 전부 매도"를 의미 — SELL 방향에서만 허용
    @AssertTrue(message = "BUY 주문의 quantity는 null일 수 없습니다")
    public boolean isBuyQuantityValid() {
        return orders == null || orders.stream()
                .filter(o -> o.direction() == OrderDirection.BUY)
                .allMatch(o -> o.quantity() != null);
    }

    // FIDA 수신 계획 주문 1건 — privacy.domain.model.FidaPlannedOrder 1:1 복제
    public record PlannedOrder(
            OrderDirection direction, // 매수/매도
            OrderType orderType,      // LOC / MOC / LIMIT
            Integer quantity,         // 주문 수량 (nullable — SELL "잔량 전부" 의미)
            BigDecimal price          // 주문 가격
    ) {
    }
}
