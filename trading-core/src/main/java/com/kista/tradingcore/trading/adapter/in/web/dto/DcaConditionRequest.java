package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.DcaOperator;
import com.kista.tradingcore.matching.domain.model.DcaCondition;

import java.math.BigDecimal;

// DCA 조건 요청 1건 — 검증은 DcaCondition(IAE→400), FEAR_GREED는 단계 ③ 전까지 차단
public record DcaConditionRequest(
        DcaIndicator indicator, // 지표
        DcaOperator operator,   // 비교 연산자
        Integer smaPeriod,      // SMA 전용
        BigDecimal threshold    // SMA 외 기준값
) {
    DcaCondition toDomain() {
        if (indicator == DcaIndicator.FEAR_GREED) {
            throw new IllegalArgumentException("공포탐욕지수 조건은 아직 지원하지 않습니다.");
        }
        return new DcaCondition(indicator, operator, smaPeriod, threshold);
    }
}
