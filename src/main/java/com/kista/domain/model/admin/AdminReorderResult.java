package com.kista.domain.model.admin;

import com.kista.domain.model.order.Order;

import java.util.UUID;

public record AdminReorderResult(
        UUID userId,
        UUID accountId,
        UUID strategyId,
        UUID sourceOrderId,              // 원본 주문 ID
        Order.OrderStatus originalStatus,
        Order.OrderStatus resultingStatus, // PLANNED / PLACED / FAILED
        String newOrderExternalId          // IMMEDIATE 즉시 접수 성공 시만 non-null
) {}
