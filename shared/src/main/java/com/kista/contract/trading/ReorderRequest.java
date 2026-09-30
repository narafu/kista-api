package com.kista.contract.trading;

import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.OrderTiming;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// 관리자 재주문 요청 — POST /api/internal/trading/reorder body
// direction/tradeDate는 미지정 시 원본 주문 값으로 대체되므로 @NotNull 대상이 아니다
public record ReorderRequest(
        @NotNull UUID userId,           // 대상 사용자
        @NotNull UUID accountId,        // 대상 계좌
        @NotNull UUID strategyId,       // 대상 전략
        @NotNull UUID orderId,          // 재주문 원본 주문
        @NotNull OrderTiming timing,    // 재주문 접수 시점 (AT_OPEN/AT_CLOSE/IMMEDIATE)
        LocalDate tradeDate,            // KST 거래일 (생략 시 원본 주문 값 사용)
        OrderDirection direction,       // 생략 시 원본 주문 값 사용
        @NotNull @Positive Integer quantity, // 재주문 수량
        @NotNull @Positive BigDecimal price, // 재주문 가격
        String memo                     // 감사 로그 메모 (선택)
) {}
