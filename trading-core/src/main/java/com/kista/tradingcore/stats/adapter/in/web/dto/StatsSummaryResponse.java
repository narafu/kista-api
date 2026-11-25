package com.kista.tradingcore.stats.adapter.in.web.dto;

import com.kista.shared.kernel.Currency;
import com.kista.tradingcore.stats.domain.model.StatsSummary;
import com.kista.tradingcore.stats.domain.model.StrategyTypeStats;

import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record StatsSummaryResponse(
        BigDecimal totalRealizedPnl,
        BigDecimal totalUnrealizedPnl,
        BigDecimal activePrincipal,
        List<TypeStats> byType,
        int totalActiveCycleCount, // 전략 타입별 진행 중 사이클 수 합계
        List<Currency> availableCurrencies
) {
    public record TypeStats(
            String type, String typeDescription,
            int closedCycleCount, int activeCycleCount,
            @Nullable BigDecimal winRate, @Nullable BigDecimal avgReturnRate, @Nullable BigDecimal avgDurationDays,
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
                summary.byType().stream().map(TypeStats::from).toList(),
                summary.byType().stream().mapToInt(StrategyTypeStats::activeCycleCount).sum(),
                summary.availableCurrencies());
    }
}
