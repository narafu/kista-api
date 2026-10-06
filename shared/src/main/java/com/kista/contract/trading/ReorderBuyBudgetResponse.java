package com.kista.contract.trading;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

// 관리자 BUY 재주문 예산 일괄 조회 — UI 경고용 (서버 차단 없음). 남은 예산은 UI가 재주문할 행만 골라
// liveOrderable − plannedBuy + Σ sourceRefunds[행 원본]으로 계산한다
public record ReorderBuyBudgetResponse(
        BigDecimal plannedBuy,                // 거래일 계좌 PLANNED BUY 합계
        BigDecimal liveOrderable,             // live 주문가능금액(usdDeposit), 조회 실패 시 null
        Map<UUID, BigDecimal> sourceRefunds   // 원본 주문별 재주문 시 취소로 되돌려 받는 BUY 금액, 해당 없으면 0
) {}
