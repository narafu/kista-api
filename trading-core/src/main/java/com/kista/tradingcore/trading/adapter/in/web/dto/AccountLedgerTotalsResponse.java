package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.tradingcore.trading.domain.model.AccountLedgerTotals;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

// 계좌 상세 "전략 합산" 응답 — 금액은 모두 계좌 통화
public record AccountLedgerTotalsResponse(
        @Schema(description = "계좌 통화", example = "USD")
        String currency,
        @Schema(description = "예수금 (전략 합산) — ARCHIVED 제외 전략들의 최신 원장 예수금 합")
        BigDecimal deposit,
        @Schema(description = "평가금 (전략 합산) — 원장 보유 × 현재가 합 (시세 조회 실패·누락이면 null)")
        @Nullable BigDecimal evaluation,
        @Schema(description = "미배정 현금 — 주문 가능 현금 − 원장 예수금 합 (모의계좌·조회 실패면 null)")
        @Nullable BigDecimal unassignedCash
) {
    public static AccountLedgerTotalsResponse from(AccountLedgerTotals totals) {
        return new AccountLedgerTotalsResponse(totals.currency().name(), totals.deposit(),
                totals.evaluation(), totals.unassignedCash());
    }
}
