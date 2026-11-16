package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaShortfallPolicy;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.tradingcore.matching.domain.model.DcaCondition;
import com.kista.tradingcore.matching.domain.model.DcaRule;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

// DCA 규칙 요청 1건 — 검증은 DcaRule(IAE→400), null 필수값은 @NotNull(400)
public record DcaRuleRequest(
        @Schema(description = "규칙 키 — 신규 null, 수정 시 기존 값 재전송(현재 버전에 없으면 400)")
        @Nullable String ruleKey,                     // 규칙 식별자
        @NotNull OrderDirection direction,  // 매수/매도
        @NotNull DcaFrequency frequency,    // 주기
        @Nullable DayOfWeek dayOfWeek,                // WEEKLY 전용
        @Nullable Integer dayOfMonth,                 // MONTHLY 전용, null=말일
        @NotNull OrderType orderType,       // MOO | MOC
        @NotNull @Valid DcaSizingRequest sizing, // 규모
        @NotNull DcaShortfallPolicy shortfall,   // 가용 부족 정책
        @Nullable List<@Nullable DcaConditionRequest> conditions     // 추가 조건(AND) — null이면 빈 목록
) {
    DcaRule toDomain() {
        if (conditions != null && conditions.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("조건 항목이 비어 있습니다.");
        }
        // null은 그대로 넘긴다 — DcaRule이 빈 목록으로 정규화
        List<DcaCondition> domainConditions = conditions == null ? null
                : conditions.stream().map(DcaConditionRequest::toDomain).toList();
        return new DcaRule(ruleKey, direction, frequency, dayOfWeek, dayOfMonth, orderType,
                sizing.toDomain(), shortfall, domainConditions);
    }
}
