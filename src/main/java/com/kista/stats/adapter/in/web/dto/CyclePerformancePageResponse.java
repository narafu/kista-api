package com.kista.stats.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kista.stats.domain.model.CyclePerformance;
import com.kista.stats.domain.model.CyclePerformancePage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CyclePerformancePageResponse(
        List<Item> items,
        @JsonInclude(JsonInclude.Include.NON_NULL) String nextCursor,
        boolean hasMore
) {
    public record Item(
            UUID cycleId, UUID accountId, String strategyType, String ticker,
            LocalDate startDate, LocalDate endDate,
            BigDecimal startAmount, BigDecimal endAmount,
            BigDecimal pnl, BigDecimal returnRate, Integer durationDays, boolean closed
    ) {
        static Item from(CyclePerformance p) {
            return new Item(p.cycleId(), p.accountId(), p.strategyType().name(),
                    p.ticker() != null ? p.ticker().name() : null,
                    p.startDate(), p.endDate(), p.startAmount(), p.endAmount(),
                    p.pnl(), p.returnRate(), p.durationDays(), p.closed());
        }
    }

    public static CyclePerformancePageResponse from(CyclePerformancePage page) {
        return new CyclePerformancePageResponse(
                page.items().stream().map(Item::from).toList(),
                page.nextCursor() != null ? page.nextCursor().toString() : null,
                page.hasMore());
    }
}
