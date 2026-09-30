package com.kista.contract.trading;

import com.kista.sharedkernel.OrderDirection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// 관리자 수동 체결 보정 요청 — POST /api/internal/trading/trade-corrections body (fills 배열 순서대로 반영)
public record TradeCorrectionRequest(
        @NotNull UUID userId,           // 대상 사용자
        @NotNull UUID accountId,        // 대상 계좌
        @NotNull UUID strategyId,       // 대상 전략
        @NotEmpty List<@Valid Fill> fills // 반영할 체결 명세
) {
    // 개별 체결 명세
    public record Fill(
            @NotNull LocalDate tradeDate,       // KST 거래일
            @NotNull OrderDirection direction,  // 매매 방향
            @Positive int quantity,             // 체결 수량
            @NotNull @Positive BigDecimal price,// 체결 가격
            String externalOrderId,             // 브로커 측 원본 주문 ID (선택)
            String memo                         // 메모 (선택)
    ) {}
}
