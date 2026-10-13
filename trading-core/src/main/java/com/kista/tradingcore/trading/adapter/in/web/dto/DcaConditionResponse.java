package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.DcaOperator;
import com.kista.tradingcore.matching.domain.model.DcaCondition;

import java.math.BigDecimal;

// DCA 조건 응답 1건 — DcaResponse.conditions
public record DcaConditionResponse(DcaIndicator indicator, DcaOperator operator, Integer smaPeriod, BigDecimal threshold) {
    static DcaConditionResponse from(DcaCondition c) {
        return new DcaConditionResponse(c.indicator(), c.operator(), c.smaPeriod(), c.threshold());
    }
}
