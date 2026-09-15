package com.kista.admin.domain.model;

import com.kista.sharedkernel.OrderTiming;
import com.kista.sharedkernel.OrderDirection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record AdminReorderCommand(
        UUID userId,
        UUID accountId,
        UUID strategyId,
        UUID orderId,
        OrderTiming timing,   // 재주문 접수 시점 (AT_OPEN/AT_CLOSE/IMMEDIATE)
        LocalDate tradeDate,   // KST 거래일
        OrderDirection direction,
        Integer quantity,
        BigDecimal price,
        String memo
) {}
