package com.kista.tradingcore.trading.domain.model;

import com.kista.shared.kernel.Currency;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;

// 계좌 원장 합산 — ARCHIVED 제외 전략들의 최신 원장 기준 (계좌 통화)
public record AccountLedgerTotals(
        Currency currency,                // 계좌 통화
        BigDecimal deposit,               // 원장 예수금 합
        @Nullable BigDecimal evaluation,  // 원장 보유 × 현재가 합 (시세 조회 실패·누락이면 null)
        @Nullable BigDecimal unassignedCash // 미배정 현금 (모의계좌·조회 실패면 null)
) {}
