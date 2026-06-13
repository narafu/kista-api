package com.kista.domain.port.out;

import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.CyclePositionHistoryEntry;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface CyclePositionPort {
    CyclePosition save(CyclePosition position);

    // 전략 기준 현재 포지션 조회 (strategy → strategy_cycle → cycle_position 조인)
    // 최신 cycle_position.createdAt 순 limit건 반환
    List<CyclePosition> findLatestByStrategyId(UUID strategyId, int limit);

    // 사이클 ID 기준 최신 N개 포지션 (리버스모드 별지점 계산용 — 최근 closing_price 추출)
    List<CyclePosition> findLatestByCycleId(UUID cycleId, int limit);

    // 계좌 ID 기준 이력 조회 (ticker 포함, 날짜 범위 필터)
    List<CyclePositionHistoryEntry> findByAccountId(UUID accountId, Instant from, Instant to);

    // 전략 ID 기준 이력 조회 (날짜 범위 필터)
    List<CyclePositionHistoryEntry> findByStrategyIdAndDateRange(UUID strategyId, Instant from, Instant to);

    // 전체 이력 중 가장 최근 N건 (대시보드·텔레그램 현황 조회)
    List<CyclePositionHistoryEntry> findRecentGlobal(int limit);

    // 날짜 범위 이력 전체 (차트용 시계열) — from 당일 00:00 KST ~ to 익일 00:00 KST
    List<CyclePositionHistoryEntry> findBetween(LocalDate from, LocalDate to);

    // 커서 기반 페이지 조회 — limit건 반환 (hasMore 판단용으로 limit+1 전달 권장)
    List<CyclePositionHistoryEntry> findByAccountIdWithCursor(UUID accountId, Instant from, Instant cursor, int limit);

    List<CyclePositionHistoryEntry> findByStrategyIdWithCursor(UUID strategyId, Instant from, Instant cursor, int limit);

    // 시드 수정 시 당일(KST) 기존 스냅샷 중복 제거
    void softDeleteTodayByStrategyId(UUID strategyId, LocalDate kstDate);

    // 전략 삭제 시 포지션 일괄 소프트 삭제
    void deleteByStrategyId(UUID strategyId);
    void deleteByAccountId(UUID accountId);
    void deleteByUserId(UUID userId);
}
