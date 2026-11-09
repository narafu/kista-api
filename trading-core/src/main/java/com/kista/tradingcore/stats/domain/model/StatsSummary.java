package com.kista.tradingcore.stats.domain.model;

import com.kista.shared.kernel.Currency;

import java.math.BigDecimal;
import java.util.List;

public record StatsSummary(
        BigDecimal totalRealizedPnl,
        BigDecimal totalUnrealizedPnl,
        BigDecimal activePrincipal, // 진행 중 사이클 개장원금 합 (VR 레거시 호환 포함)
        List<StrategyTypeStats> byType,
        List<Currency> availableCurrencies // 사용자가 가진 계좌 통화(MOCK 제외, 요청 통화와 무관)
) {}
