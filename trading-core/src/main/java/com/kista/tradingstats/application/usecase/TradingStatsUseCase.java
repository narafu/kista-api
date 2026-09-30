package com.kista.tradingstats.application.usecase;

import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;
import com.kista.tradingstats.domain.model.CyclePerformancePage;
import com.kista.tradingstats.domain.model.EquityCurve;
import com.kista.tradingstats.domain.model.StatsSummary;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// 사용자 통계 중 trading 소유 부분(실현·미실현 손익 요약/누적 자산 곡선/사이클 성과 목록) —
// housing/ETF 벤치마크 비교는 api의 UserStatsUseCase가 계속 소유한다
public interface TradingStatsUseCase {
    StatsSummary getSummary(UUID userId);

    // from/to null 허용 (null이면 전체/오늘)
    EquityCurve getEquityCurve(UUID userId, StrategyType type, LocalDate from, LocalDate to);

    // type/accountId/ticker 각각 null이면 해당 조건 미적용 (AND 조합). 타 사용자 accountId는 빈 결과
    CyclePerformancePage getCyclePerformances(UUID userId, StrategyType type, UUID accountId, StrategyTicker ticker,
                                              Instant cursor, int size);
}
