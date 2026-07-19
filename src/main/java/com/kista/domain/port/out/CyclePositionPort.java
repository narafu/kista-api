package com.kista.domain.port.out;

import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.CyclePositionHistoryEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CyclePositionPort {
    CyclePosition save(CyclePosition position);

    // 전략 기준 현재 포지션 조회 (strategy → strategy_cycle → cycle_position 조인)
    // 최신 cycle_position.createdAt 순 limit건 반환
    List<CyclePosition> findLatestByStrategyId(UUID strategyId, int limit);

    // 사이클 ID 기준 최신 N개 포지션 (리버스모드 별지점 계산용 — 최근 closing_price 추출)
    List<CyclePosition> findLatestByCycleId(UUID cycleId, int limit);

    // 사이클 ID 기준 최신 포지션 1건 — findLatestByCycleId(id, 1).stream().findFirst() 축약
    default Optional<CyclePosition> findLatestOne(UUID cycleId) {
        return findLatestByCycleId(cycleId, 1).stream().findFirst();
    }

    // 전략 ID 기준 최신 포지션 1건 — findLatestByStrategyId(id, 1).stream().findFirst() 축약
    default Optional<CyclePosition> findLatestOneByStrategyId(UUID strategyId) {
        return findLatestByStrategyId(strategyId, 1).stream().findFirst();
    }

    // 계좌 ID 기준 이력 조회 (ticker 포함, 날짜 범위 필터)
    List<CyclePositionHistoryEntry> findByAccountId(UUID accountId, Instant from, Instant to);

    // 전략 ID 기준 이력 조회 (날짜 범위 필터)
    List<CyclePositionHistoryEntry> findByStrategyIdAndDateRange(UUID strategyId, Instant from, Instant to);

    // 사용자 스코프 최근 N건 (대시보드 — 본인 데이터만)
    List<CyclePositionHistoryEntry> findRecentByUser(UUID userId, int limit);

    // 사용자 스코프 스냅샷 범위 조회 (통계 equity curve용) — created_at 오름차순
    List<CyclePosition> findByUserAndRange(UUID userId, Instant from, Instant to);

    // 개별 전략 스코프 월별 수익률 계산용 — created_at 오름차순
    List<CyclePosition> findByStrategyAndRange(UUID strategyId, Instant from, Instant to);

    // 커서 기반 페이지 조회 — limit건 반환 (hasMore 판단용으로 limit+1 전달 권장)
    List<CyclePositionHistoryEntry> findByAccountIdWithCursor(UUID accountId, Instant from, Instant cursor, int limit);

    List<CyclePositionHistoryEntry> findByStrategyIdWithCursor(UUID strategyId, Instant from, Instant cursor, int limit);

    // holdings=0 시작점 시드 수정 — 최신 시작점 스냅샷 in-place 갱신
    void updateCycleStartSnapshot(UUID strategyId, BigDecimal newDeposit);

    // 전략 삭제 시 포지션 일괄 소프트 삭제
    void deleteByStrategyId(UUID strategyId);
    void deleteByAccountId(UUID accountId);
    void deleteByUserId(UUID userId);
}
