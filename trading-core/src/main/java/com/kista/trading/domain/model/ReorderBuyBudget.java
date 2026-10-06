package com.kista.trading.domain.model;

import java.math.BigDecimal;

// BUY 재주문 예산 — remaining = liveOrderable - plannedBuy + sourceRefund
public record ReorderBuyBudget(
        BigDecimal plannedBuy,     // 거래일 계좌 PLANNED BUY 합계
        BigDecimal sourceRefund,   // 원본 BUY 취소로 풀려나는 금액(없으면 0)
        BigDecimal liveOrderable,  // live 주문가능금액 (조회 실패 시 null)
        BigDecimal remaining       // 남은 예산 (live 실패 시 null)
) {}
