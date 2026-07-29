package com.kista.domain.model.stats;

import java.math.BigDecimal;
import java.util.List;

public record StatsSummary(
        BigDecimal totalRealizedPnl,
        BigDecimal totalUnrealizedPnl,
        BigDecimal activePrincipal, // 진행 중 사이클 개장원금 합 (VR 레거시 호환 포함)
        List<StrategyTypeStats> byType
) {}
