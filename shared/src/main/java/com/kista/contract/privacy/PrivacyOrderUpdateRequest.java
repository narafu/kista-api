package com.kista.contract.privacy;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

// 관리자 PRIVACY 주문 명세 수동 보정 — PATCH /api/internal/privacy/trade-bases/{baseId}/orders/{orderId} body (가격·수량만 교체)
public record PrivacyOrderUpdateRequest(
        @NotNull @Positive BigDecimal price,  // 주문 가격
        @Positive Integer quantity            // 주문 수량 (nullable, 값이 있으면 양수) — BUY 주문의 null 여부는 서버가 대상 주문 방향으로 검증
) {}
