package com.kista.domain.model.stats;

import java.math.BigDecimal;
import java.util.List;

public record StatsSummary(
        BigDecimal totalRealizedPnl,
        BigDecimal totalUnrealizedPnl,
        BigDecimal activePrincipal, // 진행 중 사이클 startAmount 합
        List<StrategyTypeStats> byType
) {}
