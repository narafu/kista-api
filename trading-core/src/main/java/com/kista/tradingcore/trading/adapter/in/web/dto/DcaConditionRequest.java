package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.DcaOperator;
import com.kista.tradingcore.matching.domain.model.DcaCondition;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

// DCA 조건 요청 1건 — 검증은 DcaCondition(IAE→400)
public record DcaConditionRequest(
        @Nullable DcaIndicator indicator, // 지표
        @Nullable DcaOperator operator,   // 비교 연산자
        @Nullable Integer smaPeriod,      // SMA 전용
        @Nullable BigDecimal threshold    // SMA 외 기준값
) {
    DcaCondition toDomain() {
        return new DcaCondition(indicator, operator, smaPeriod, threshold);
    }
}
