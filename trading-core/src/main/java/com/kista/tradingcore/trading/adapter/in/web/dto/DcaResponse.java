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
        OrderDirection direction, BigDecimal amountUsd, OrderType orderType, DcaFrequency frequency,
        DayOfWeek dayOfWeek, Integer dayOfMonth,
        List<Object> conditions,          // 단계 ①은 항상 빈 배열
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
        return new DcaResponse(c.direction(), c.amountUsd(), c.orderType(), c.frequency(), c.dayOfWeek(), c.dayOfMonth(),
                List.of(), next, last);
    }
}
