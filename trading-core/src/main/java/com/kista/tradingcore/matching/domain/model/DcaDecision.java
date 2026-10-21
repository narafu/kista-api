package com.kista.tradingcore.matching.domain.model;

import com.kista.shared.kernel.DcaSkipReason;

import java.math.BigDecimal;

// DCA 조건 판정의 skip 결과(사유·상세·기준가) — DcaConditions.check 반환 타입. 규칙 단위 판정 결과는 DcaRuleDecision
public sealed interface DcaDecision permits DcaDecision.Skipped {

    record Skipped(DcaSkipReason reason, String detail, BigDecimal referencePrice) implements DcaDecision {}
}
