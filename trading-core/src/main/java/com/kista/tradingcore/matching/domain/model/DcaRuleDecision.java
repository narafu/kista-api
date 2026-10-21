package com.kista.tradingcore.matching.domain.model;

import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;

import java.math.BigDecimal;

// DCA 규칙 1개의 판정 결과 — 주문 계획 또는 skip
public sealed interface DcaRuleDecision permits DcaRuleDecision.Planned, DcaRuleDecision.Skipped {

    // quantity: 주식은 정수. notional: 크립토 BUY 주문 금액(그 외 null). limitPrice: 주식 BUY 캡가, 그 외 기준가
    record Planned(String ruleKey, OrderDirection direction, BigDecimal quantity, BigDecimal notional,
                   BigDecimal referencePrice, BigDecimal limitPrice) implements DcaRuleDecision {}

    record Skipped(String ruleKey, DcaSkipReason reason, String detail, BigDecimal referencePrice) implements DcaRuleDecision {}
}
