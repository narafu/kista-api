package com.kista.domain.model.admin;

import com.kista.domain.model.order.Order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// 관리자 수동 체결 보정 입력 — fills 배열 순서대로 반영
public record AdminManualTradeCorrectionCommand(
        UUID userId,
        UUID accountId,
        UUID strategyId,
        List<Fill> fills
) {
    public record Fill(
            LocalDate tradeDate,   // KST 거래일
            Order.OrderDirection direction,
            int quantity,
            BigDecimal price,
            String externalOrderId,
            String memo
    ) {}
}
