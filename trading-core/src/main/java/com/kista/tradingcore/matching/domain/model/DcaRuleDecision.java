package com.kista.tradingcore.matching.domain.model;

import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;

import java.math.BigDecimal;

// DCA 규칙 1개의 판정 결과 — 주문 계획 또는 skip
public sealed interface DcaRuleDecision permits DcaRuleDecision.Planned, DcaRuleDecision.Skipped {

    // quantity: 정수 주. limitPrice: BUY 캡가, SELL은 기준가
    record Planned(String ruleKey, OrderDirection direction, BigDecimal quantity,
                   BigDecimal referencePrice, BigDecimal limitPrice) implements DcaRuleDecision {}

    record Skipped(String ruleKey, DcaSkipReason reason, String detail, BigDecimal referencePrice) implements DcaRuleDecision {}
}
