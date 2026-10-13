package com.kista.tradingcore.matching.domain.model;

import com.kista.shared.kernel.DcaSkipReason;

import java.math.BigDecimal;

// DCA 회차 판정 결과 — 주문 계획 또는 skip(사유·상세)
public sealed interface DcaDecision permits DcaDecision.Planned, DcaDecision.Skipped {

    record Planned(PlannedOrder order, BigDecimal referencePrice) implements DcaDecision {}

    record Skipped(DcaSkipReason reason, String detail, BigDecimal referencePrice) implements DcaDecision {}
}
