package com.kista.admin.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

// BUY 재주문 예산 응답 — UI 가격×수량 초과 경고용
public record ReorderBuyBudgetResponse(
        @Schema(description = "거래일 계좌 PLANNED BUY 합계")
        BigDecimal plannedBuy,     // 거래일 계좌 PLANNED BUY 합계
        @Schema(description = "재주문이 취소할 원본 BUY 금액(되돌려 받는 몫), 해당 없으면 0")
        BigDecimal sourceRefund,   // 원본 BUY 환급분
        @Schema(description = "live 주문가능금액(USD), 조회 실패 시 null", nullable = true)
        BigDecimal liveOrderable,  // live 주문가능금액
        @Schema(description = "남은 예산 = liveOrderable − plannedBuy + sourceRefund, live 실패 시 null", nullable = true)
        BigDecimal remaining       // 남은 예산
) {
    public static ReorderBuyBudgetResponse from(com.kista.contract.trading.ReorderBuyBudgetResponse b) {
        return new ReorderBuyBudgetResponse(b.plannedBuy(), b.sourceRefund(), b.liveOrderable(), b.remaining());
    }
}
