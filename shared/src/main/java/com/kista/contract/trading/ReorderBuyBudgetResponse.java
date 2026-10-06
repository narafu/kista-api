package com.kista.contract.trading;

import java.math.BigDecimal;

// 관리자 BUY 재주문 예산 조회 — UI 경고용 (서버 차단 없음)
public record ReorderBuyBudgetResponse(
        BigDecimal plannedBuy,     // 거래일 계좌 PLANNED BUY 합계
        BigDecimal sourceRefund,   // 재주문이 취소할 원본 BUY 금액(되돌려 받는 몫), 해당 없으면 0
        BigDecimal liveOrderable,  // live 주문가능금액(usdDeposit), 조회 실패 시 null
        BigDecimal remaining       // liveOrderable - plannedBuy + sourceRefund, live 실패 시 null
) {}
