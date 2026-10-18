package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import com.kista.tradingcore.trading.domain.model.DcaExecution;
import com.kista.tradingcore.trading.domain.model.DcaSummary;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

// DCA 조회 응답 블록 — TradingCycleResponse.dca
public record DcaResponse(
        OrderDirection direction,
        BigDecimal amount,                // 회차당 금액 (계좌 통화)
        OrderType orderType, DcaFrequency frequency,
        DayOfWeek dayOfWeek, Integer dayOfMonth,
        Integer executionHour,            // 코인 실행 시각 (KST, 주식 null)
        List<DcaConditionResponse> conditions, // 추가 조건(AND), 입력 순서
        NextExecution nextExecution,      // 보정 반영 판정 예정일 (null 가능)
        LastExecution lastExecution       // 최근 회차 (null 가능)
) {
    public record NextExecution(LocalDate usSessionDate, LocalDate kstTradeDate) {}

    public record LastExecution(LocalDate kstTradeDate, String status, String skipReason) {}

    public static DcaResponse from(DcaSummary s) {
        DcaConfig c = s.config();
        NextExecution next = s.nextKstTradeDate() == null ? null : new NextExecution(s.nextUsSessionDate(), s.nextKstTradeDate());
        DcaExecution e = s.lastExecution();
        LastExecution last = e == null ? null : new LastExecution(e.tradeDate(), e.status().name(),
                e.skipReason() == null ? null : e.skipReason().name());
        return new DcaResponse(c.direction(), c.amount(), c.orderType(), c.frequency(), c.dayOfWeek(), c.dayOfMonth(),
                c.executionHour(), c.conditions().stream().map(DcaConditionResponse::from).toList(), next, last);
    }
}
