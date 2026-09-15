package com.kista.trading.stats.application.usecase;

import com.kista.sharedkernel.StrategyType;
import com.kista.trading.stats.domain.model.CyclePerformancePage;
import com.kista.trading.stats.domain.model.EquityCurve;
import com.kista.trading.stats.domain.model.StatsSummary;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// 사용자 통계 중 trading 소유 부분(실현·미실현 손익 요약/누적 자산 곡선/사이클 성과 목록) —
// housing/ETF 벤치마크 비교는 api의 UserStatsUseCase가 계속 소유한다
public interface TradingStatsUseCase {
    StatsSummary getSummary(UUID userId);

    // from/to null 허용 (null이면 전체/오늘)
    EquityCurve getEquityCurve(UUID userId, StrategyType type, LocalDate from, LocalDate to);

    // type null이면 전체
    CyclePerformancePage getCyclePerformances(UUID userId, StrategyType type, Instant cursor, int size);
}
