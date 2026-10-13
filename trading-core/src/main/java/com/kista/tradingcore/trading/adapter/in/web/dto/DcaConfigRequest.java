package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.tradingcore.matching.domain.model.DcaCondition;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.List;

// DCA 등록·수정 요청 본문
public record DcaConfigRequest(
        @NotNull OrderDirection direction,  // 매수/매도
        @NotNull BigDecimal amountUsd,      // 회차당 금액 (USD)
        @NotNull OrderType orderType,       // MOO | MOC
        @NotNull DcaFrequency frequency,    // 주기
        DayOfWeek dayOfWeek,                // WEEKLY 전용
        Integer dayOfMonth,                 // MONTHLY 전용, null=말일
        List<DcaConditionRequest> conditions // 추가 조건(AND) — null이면 빈 목록
) {
    public DcaConfig toDomain() {
        if (conditions != null && conditions.contains(null)) {
            throw new IllegalArgumentException("조건 항목이 비어 있습니다.");
        }
        List<DcaCondition> domainConditions = conditions == null ? List.of()
                : conditions.stream().map(DcaConditionRequest::toDomain).toList();
        return new DcaConfig(direction, amountUsd, orderType, frequency, dayOfWeek, dayOfMonth, domainConditions);
    }
}
