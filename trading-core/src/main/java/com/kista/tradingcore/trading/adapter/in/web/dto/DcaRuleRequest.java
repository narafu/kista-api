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

// DCA 규칙 요청 1건 — 검증은 DcaRule(IAE→400), null 필수값은 @NotNull(400)
public record DcaRuleRequest(
        @Schema(description = "규칙 키 — 신규 null, 수정 시 기존 값 재전송(현재 버전에 없으면 400)")
        String ruleKey,                     // 규칙 식별자
        @NotNull OrderDirection direction,  // 매수/매도
        @NotNull DcaFrequency frequency,    // 주기
        DayOfWeek dayOfWeek,                // WEEKLY 전용
        Integer dayOfMonth,                 // MONTHLY 전용, null=말일
        @NotNull OrderType orderType,       // 주식 MOO | MOC, 코인 MARKET
        @Schema(description = "코인 실행 시각 (KST 0~23, 코인 필수). 주식 주문유형이면 무시")
        Integer executionHour,              // 코인 전용 실행 시각
        @NotNull @Valid DcaSizingRequest sizing, // 규모
        @NotNull DcaShortfallPolicy shortfall,   // 가용 부족 정책
        List<DcaConditionRequest> conditions     // 추가 조건(AND) — null이면 빈 목록
) {
    DcaRule toDomain() {
        if (conditions != null && conditions.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("조건 항목이 비어 있습니다.");
        }
        List<DcaCondition> domainConditions = conditions == null ? List.of()
                : conditions.stream().map(DcaConditionRequest::toDomain).toList();
        // 주식 주문유형이면 실행 시각을 저장하지 않는다(무시) — 커널 검증은 방어선
        Integer hour = orderType == OrderType.MARKET ? executionHour : null;
        return new DcaRule(ruleKey, direction, frequency, dayOfWeek, dayOfMonth, orderType, hour,
                sizing.toDomain(), shortfall, domainConditions);
    }
}
