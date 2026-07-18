package com.kista.domain.port.in;

import com.kista.domain.model.stats.CyclePerformancePage;
import com.kista.domain.model.stats.EquityCurve;
import com.kista.domain.model.stats.StatsSummary;
import com.kista.domain.model.strategy.Strategy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public interface UserStatsUseCase {
    StatsSummary getSummary(UUID userId);

    // from/to null 허용 (null이면 전체/오늘), benchmarkSymbol: "SPY"|"QQQ"|"QLD"
    EquityCurve getEquityCurve(UUID userId, LocalDate from, LocalDate to, String benchmarkSymbol);

    // type null이면 전체
    CyclePerformancePage getCyclePerformances(UUID userId, Strategy.Type type, Instant cursor, int size);
}
