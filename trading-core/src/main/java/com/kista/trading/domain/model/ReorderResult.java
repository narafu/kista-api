package com.kista.trading.domain.model;

import com.kista.sharedkernel.OrderStatus;
import com.kista.sharedkernel.OrderDirection;

import java.math.BigDecimal;
import java.util.UUID;

public record ReorderResult(
        UUID userId,
        UUID accountId,
        UUID strategyId,
        UUID sourceOrderId,              // 원본 주문 ID
        OrderStatus originalStatus,
        OrderStatus resultingStatus, // PLANNED / PLACED / FAILED
        String newOrderExternalId,         // IMMEDIATE 즉시 접수 성공 시만 non-null
        BigDecimal oldPrice,                // 원본 주문 가격 — admin 감사 로그용
        Integer oldQuantity,                 // 원본 주문 수량 — admin 감사 로그용
        OrderDirection newDirection           // 실제 적용된 재주문 방향 — admin 감사 로그용
) {}

