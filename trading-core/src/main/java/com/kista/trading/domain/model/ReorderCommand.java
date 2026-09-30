package com.kista.trading.domain.model;

import com.kista.sharedkernel.OrderTiming;
import com.kista.sharedkernel.OrderDirection;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// 재주문 도메인 command — 내부 API body는 contract.trading.ReorderRequest이고 TradingContractMapper가 이 타입으로 변환한다.
// direction/tradeDate는 미지정 시 원본 주문 값으로 대체되므로 @NotNull 대상이 아니다.
public record ReorderCommand(
        @NotNull UUID userId,
        @NotNull UUID accountId,
        @NotNull UUID strategyId,
        @NotNull UUID orderId,
        @NotNull OrderTiming timing,   // 재주문 접수 시점 (AT_OPEN/AT_CLOSE/IMMEDIATE)
        LocalDate tradeDate,   // KST 거래일 (생략 시 원본 주문 값 사용)
        OrderDirection direction,   // 생략 시 원본 주문 값 사용
        @NotNull @Positive Integer quantity,
        @NotNull @Positive BigDecimal price,
        String memo
) {}
