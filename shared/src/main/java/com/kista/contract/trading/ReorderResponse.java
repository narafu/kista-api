package com.kista.contract.trading;

import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.OrderStatus;

import java.math.BigDecimal;
import java.util.UUID;

// 관리자 재주문 결과 — POST /api/internal/trading/reorder 응답
public record ReorderResponse(
        UUID userId,                    // 대상 사용자
        UUID accountId,                 // 대상 계좌
        UUID strategyId,                // 대상 전략
        UUID sourceOrderId,             // 원본 주문 ID
        OrderStatus originalStatus,     // 원본 주문 상태
        OrderStatus resultingStatus,    // PLANNED / PLACED / FAILED
        String newOrderExternalId,      // IMMEDIATE 즉시 접수 성공 시만 non-null
        BigDecimal oldPrice,            // 원본 주문 가격 — 감사 로그용
        Integer oldQuantity,            // 원본 주문 수량 — 감사 로그용
        OrderDirection newDirection     // 실제 적용된 재주문 방향 — 감사 로그용
) {}
