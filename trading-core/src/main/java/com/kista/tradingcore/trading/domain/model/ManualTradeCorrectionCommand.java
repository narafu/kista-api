package com.kista.tradingcore.trading.domain.model;

import com.kista.shared.kernel.OrderDirection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// 관리자 수동 체결 보정 입력 — fills 배열 순서대로 반영
public record ManualTradeCorrectionCommand(
        UUID userId,
        UUID accountId,
        UUID strategyId,
        List<Fill> fills
) {
    public record Fill(
            LocalDate tradeDate,   // KST 거래일
            OrderDirection direction,
            BigDecimal quantity, // 정수 주 (서비스가 검증)
            BigDecimal price,
            String externalOrderId,   // 브로커 측 원본 주문 ID (선택)
            String memo
    ) {}
}
