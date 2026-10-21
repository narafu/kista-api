package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaShortfallPolicy;
import com.kista.shared.kernel.DcaSizingType;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.tradingcore.matching.domain.model.DcaRule;
import com.kista.tradingcore.trading.domain.model.DcaExecution;
import com.kista.tradingcore.trading.domain.model.DcaSummary;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

// DCA 규칙 응답 1건 — DcaResponse.rules (요청 필드 + 확정 ruleKey + 규칙별 다음·최근 회차)
public record DcaRuleResponse(
        String ruleKey,                   // 확정 규칙 키
        OrderDirection direction,
        DcaFrequency frequency, DayOfWeek dayOfWeek, Integer dayOfMonth,
        OrderType orderType,
        Integer executionHour,            // 코인 실행 시각 (KST, 주식 null)
        Sizing sizing,                    // 규모
        DcaShortfallPolicy shortfall,     // 가용 부족 정책
        List<DcaConditionResponse> conditions, // 추가 조건(AND), 입력 순서
        NextExecution nextExecution,      // 보정 반영 판정 예정일 (null 가능)
        LastExecution lastExecution       // 이 규칙의 최근 회차 (null 가능)
) {
    public record Sizing(DcaSizingType type, BigDecimal amount, BigDecimal percent) {}

    public record NextExecution(LocalDate usSessionDate, LocalDate kstTradeDate) {}

    public record LastExecution(LocalDate kstTradeDate, String status, String skipReason) {}

    static DcaRuleResponse from(DcaSummary.RuleSummary s) {
        DcaRule r = s.rule();
        NextExecution next = s.nextKstTradeDate() == null ? null : new NextExecution(s.nextUsSessionDate(), s.nextKstTradeDate());
        DcaExecution e = s.lastExecution();
        LastExecution last = e == null ? null : new LastExecution(e.tradeDate(), e.status().name(),
                e.skipReason() == null ? null : e.skipReason().name());
        return new DcaRuleResponse(r.ruleKey(), r.direction(), r.frequency(), r.dayOfWeek(), r.dayOfMonth(), r.orderType(),
                r.executionHour(), new Sizing(r.sizing().type(), r.sizing().amount(), r.sizing().percent()), r.shortfall(),
                r.conditions().stream().map(DcaConditionResponse::from).toList(), next, last);
    }
}
