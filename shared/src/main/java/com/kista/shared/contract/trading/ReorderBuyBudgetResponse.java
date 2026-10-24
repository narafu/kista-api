package com.kista.shared.contract.trading;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

// 관리자 BUY 재주문 예산 일괄 조회 — 서버도 재주문 시 원장 상한을 검증한다(409). UI는 재주문할 행의 원본 사이클 한도로 경고한다
// 가용 = cycleRemaining[원본 strategyCycleId] + Σ sourceRefunds[행 원본]
public record ReorderBuyBudgetResponse(
        Map<UUID, BigDecimal> cycleRemaining, // strategyCycleId별 남은 원장 한도(원장 − 같은 거래일 저장된 BUY)
        Map<UUID, BigDecimal> sourceRefunds   // 원본 주문별 재주문 시 취소로 되돌려 받는 BUY 금액, 해당 없으면 0
) {}
