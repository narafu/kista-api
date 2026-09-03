package com.kista.stats.adapter.in.web.dto;

import com.kista.stats.domain.model.StatsSummary;
import com.kista.stats.domain.model.StrategyTypeStats;

import java.math.BigDecimal;
import java.util.List;

public record StatsSummaryResponse(
        BigDecimal totalRealizedPnl,
        BigDecimal totalUnrealizedPnl,
        BigDecimal activePrincipal,
        List<TypeStats> byType
) {
    public record TypeStats(
            String type, String typeDescription,
            int closedCycleCount, int activeCycleCount,
            BigDecimal winRate, BigDecimal avgReturnRate, BigDecimal avgDurationDays,
            BigDecimal realizedPnl, BigDecimal unrealizedPnl
    ) {
        static TypeStats from(StrategyTypeStats s) {
            return new TypeStats(s.type().name(), s.type().getDescription(),
                    s.closedCycleCount(), s.activeCycleCount(),
                    s.winRate(), s.avgReturnRate(), s.avgDurationDays(),
                    s.realizedPnl(), s.unrealizedPnl());
        }
    }

    public static StatsSummaryResponse from(StatsSummary summary) {
        return new StatsSummaryResponse(summary.totalRealizedPnl(), summary.totalUnrealizedPnl(),
                summary.activePrincipal(),
                summary.byType().stream().map(TypeStats::from).toList());
    }
}
