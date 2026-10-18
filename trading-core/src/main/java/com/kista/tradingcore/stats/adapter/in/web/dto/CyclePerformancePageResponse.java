package com.kista.tradingcore.stats.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.kista.tradingcore.stats.domain.model.CyclePerformance;
import com.kista.tradingcore.stats.domain.model.CyclePerformancePage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CyclePerformancePageResponse(
        List<Item> items,
        int page, // 0-based
        long totalElements,
        int totalPages
) {
    @Schema(name = "CyclePerformanceItem")
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
                page.page(), page.totalElements(), page.totalPages());
    }
}
