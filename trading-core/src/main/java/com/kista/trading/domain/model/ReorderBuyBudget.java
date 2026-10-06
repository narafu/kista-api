package com.kista.trading.domain.model;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

// BUY 재주문 예산 — 남은 예산 = liveOrderable − plannedBuy + 재주문할 원본들의 sourceRefunds 합
public record ReorderBuyBudget(
        BigDecimal plannedBuy,                // 거래일 계좌 PLANNED BUY 합계
        BigDecimal liveOrderable,             // live 주문가능금액, 조회 실패 시 null
        Map<UUID, BigDecimal> sourceRefunds   // 원본 주문별 환급분(취소로 풀려나는 BUY 금액)
) {}
