package com.kista.shared.contract.trading;

import com.kista.shared.kernel.OrderDirection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

// 관리자 수동 체결 보정 요청 — POST /api/internal/trading/trade-corrections body (fills 배열 순서대로 반영)
public record TradeCorrectionRequest(
        @NotNull(message = "대상 사용자는 필수입니다.") UUID userId,           // 대상 사용자
        @NotNull(message = "대상 계좌는 필수입니다.") UUID accountId,        // 대상 계좌
        @NotNull(message = "대상 전략은 필수입니다.") UUID strategyId,       // 대상 전략
        @NotEmpty(message = "체결 명세를 하나 이상 입력해야 합니다.") List<@Valid Fill> fills // 반영할 체결 명세
) {
    // 개별 체결 명세
    public record Fill(
            @NotNull(message = "거래일은 필수입니다.") LocalDate tradeDate,       // KST 거래일
            @NotNull(message = "매매 방향은 필수입니다.") OrderDirection direction,  // 매매 방향
            @NotNull(message = "체결 수량은 필수입니다.") @Positive(message = "체결 수량은 0보다 커야 합니다.") BigDecimal quantity, // 체결 수량 (정수)
            @NotNull(message = "체결 가격은 필수입니다.") @Positive(message = "체결 가격은 0보다 커야 합니다.") BigDecimal price,// 체결 가격
            @Nullable String externalOrderId,             // 브로커 측 원본 주문 ID (선택)
            @Nullable String memo                         // 메모 (선택)
    ) {}
}
