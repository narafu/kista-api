package com.kista.domain.model.admin;

import com.kista.domain.model.order.Order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record AdminReorderCommand(
        UUID userId,
        UUID accountId,
        UUID strategyId,
        UUID orderId,
        Order.OrderTiming timing,   // 재주문 접수 시점 (AT_OPEN/AT_CLOSE/IMMEDIATE)
        LocalDate tradeDate,   // KST 거래일
        Order.OrderDirection direction,
        Integer quantity,
        BigDecimal price,
        String memo
) {}
