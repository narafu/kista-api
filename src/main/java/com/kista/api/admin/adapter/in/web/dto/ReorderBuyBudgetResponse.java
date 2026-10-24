package com.kista.api.admin.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

// BUY 재주문 예산 응답 — UI 가격×수량 초과 경고용
public record ReorderBuyBudgetResponse(
        @Schema(description = "전략 사이클 ID별 남은 원장 한도(USD) = 최신 원장 − 같은 거래일 저장된 BUY")
        Map<UUID, BigDecimal> cycleRemaining, // 사이클별 남은 한도
        @Schema(description = "원본 주문 ID별 재주문 시 취소로 되돌려 받는 BUY 금액(해당 없으면 0)")
        Map<UUID, BigDecimal> sourceRefunds   // 원본별 환급분
) {
    public static ReorderBuyBudgetResponse from(com.kista.shared.contract.trading.ReorderBuyBudgetResponse b) {
        return new ReorderBuyBudgetResponse(b.cycleRemaining(), b.sourceRefunds());
    }
}
