package com.kista.admin.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

// BUY 재주문 예산 응답 — UI 가격×수량 초과 경고용
public record ReorderBuyBudgetResponse(
        @Schema(description = "거래일 계좌 PLANNED BUY 합계")
        BigDecimal plannedBuy,                // 거래일 계좌 PLANNED BUY 합계
        @Schema(description = "live 주문가능금액(USD), 조회 실패 시 null", nullable = true)
        BigDecimal liveOrderable,             // live 주문가능금액
        @Schema(description = "원본 주문 ID별 재주문 시 취소로 되돌려 받는 BUY 금액(해당 없으면 0)")
        Map<UUID, BigDecimal> sourceRefunds   // 원본별 환급분
) {
    public static ReorderBuyBudgetResponse from(com.kista.contract.trading.ReorderBuyBudgetResponse b) {
        return new ReorderBuyBudgetResponse(b.plannedBuy(), b.liveOrderable(), b.sourceRefunds());
    }
}
