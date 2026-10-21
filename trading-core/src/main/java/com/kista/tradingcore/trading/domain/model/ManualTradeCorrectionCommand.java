package com.kista.tradingcore.trading.domain.model;

import com.kista.shared.kernel.OrderDirection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// 관리자 수동 체결 보정 입력 — fills 배열 순서대로 반영
public record ManualTradeCorrectionCommand(
        @NotNull UUID userId,
        @NotNull UUID accountId,
        @NotNull UUID strategyId,
        @NotEmpty List<@Valid Fill> fills
) {
    public record Fill(
            @NotNull LocalDate tradeDate,   // KST 거래일
            @NotNull OrderDirection direction,
            @NotNull @Positive BigDecimal quantity, // 주식은 정수, 코인은 소수 8자리까지 (서비스가 계좌 시장으로 검증)
            @NotNull @Positive BigDecimal price,
            String externalOrderId,   // 브로커 측 원본 주문 ID (선택)
            String memo
    ) {}
}
