package com.kista.adapter.out.persistence.strategy;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface CyclePositionJpaRepository extends JpaRepository<CyclePositionEntity, UUID> {

    // strategy_cycle_id 기준 최신 N건 (@SQLRestriction: deleted_at IS NULL 자동 적용)
    List<CyclePositionEntity> findTopNByStrategyCycleIdOrderByCreatedAtDesc(UUID strategyCycleId, Pageable pageable);

    // 전략 ID 기준 날짜 범위 조회 (strategy_cycle 경유 JOIN — native)
    @Query(value = """
            SELECT cp.* FROM cycle_position cp
            JOIN strategy_cycle sc ON cp.strategy_cycle_id = sc.id
            WHERE sc.strategy_id = :strategyId
              AND cp.created_at >= :from AND cp.created_at < :to
              AND cp.deleted_at IS NULL AND sc.deleted_at IS NULL
            ORDER BY cp.created_at DESC
            """, nativeQuery = true)
    List<CyclePositionEntity> findByStrategyIdAndDateRange(
            @Param("strategyId") UUID strategyId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    // 계좌 ID 기준 날짜 범위 조회 (strategy_cycle → strategy 경유 JOIN — native)
    @Query(value = """
            SELECT cp.* FROM cycle_position cp
            JOIN strategy_cycle sc ON cp.strategy_cycle_id = sc.id
            JOIN strategy s ON sc.strategy_id = s.id
            WHERE s.account_id = :accountId
              AND cp.created_at >= :from AND cp.created_at < :to
              AND cp.deleted_at IS NULL AND sc.deleted_at IS NULL AND s.deleted_at IS NULL
            ORDER BY cp.created_at DESC
            """, nativeQuery = true)
    List<CyclePositionEntity> findByAccountIdAndDateRange(
            @Param("accountId") UUID accountId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    // 전체 이력 최근 N건 — 대시보드·텔레그램 현황용 (@SQLRestriction 적용)
    List<CyclePositionEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    // 날짜 범위 이력 전체 — 차트용 시계열 (@SQLRestriction 적용)
    @Query("SELECT cp FROM CyclePositionEntity cp " +
           "WHERE cp.createdAt >= :from AND cp.createdAt < :to " +
           "ORDER BY cp.createdAt DESC")
    List<CyclePositionEntity> findBetweenDates(@Param("from") Instant from, @Param("to") Instant to);

    // 사용자 스코프 최근 N건 (대시보드 — 본인 데이터만, native)
    @Query(value = """
            SELECT cp.* FROM cycle_position cp
            JOIN strategy_cycle sc ON cp.strategy_cycle_id = sc.id
            JOIN strategy s ON sc.strategy_id = s.id
            JOIN accounts a ON s.account_id = a.id
            WHERE a.user_id = :userId
              AND cp.deleted_at IS NULL AND sc.deleted_at IS NULL AND s.deleted_at IS NULL AND a.deleted_at IS NULL
            ORDER BY cp.created_at DESC
            """, nativeQuery = true)
    List<CyclePositionEntity> findRecentByUserId(@Param("userId") UUID userId, Pageable pageable);

    // 사용자 스코프 날짜 범위 (차트용 시계열, native)
    @Query(value = """
            SELECT cp.* FROM cycle_position cp
            JOIN strategy_cycle sc ON cp.strategy_cycle_id = sc.id
            JOIN strategy s ON sc.strategy_id = s.id
            JOIN accounts a ON s.account_id = a.id
            WHERE a.user_id = :userId
              AND cp.created_at >= :from AND cp.created_at < :to
              AND cp.deleted_at IS NULL AND sc.deleted_at IS NULL AND s.deleted_at IS NULL
            ORDER BY cp.created_at DESC
            """, nativeQuery = true)
    List<CyclePositionEntity> findBetweenDatesByUserId(
            @Param("userId") UUID userId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    // 계좌 기준 커서 페이지네이션 (native — strategy_cycle → strategy JOIN)
    @Query(value = """
            SELECT cp.* FROM cycle_position cp
            JOIN strategy_cycle sc ON cp.strategy_cycle_id = sc.id
            JOIN strategy s ON sc.strategy_id = s.id
            WHERE s.account_id = :accountId
              AND cp.created_at >= :from AND cp.created_at < :cursor
              AND cp.deleted_at IS NULL AND sc.deleted_at IS NULL AND s.deleted_at IS NULL
            ORDER BY cp.created_at DESC
            """, nativeQuery = true)
    List<CyclePositionEntity> findByAccountIdWithCursor(
            @Param("accountId") UUID accountId,
            @Param("from") Instant from,
            @Param("cursor") Instant cursor,
            Pageable pageable);

    // 전략 기준 커서 페이지네이션 (native)
    @Query(value = """
            SELECT cp.* FROM cycle_position cp
            JOIN strategy_cycle sc ON cp.strategy_cycle_id = sc.id
            WHERE sc.strategy_id = :strategyId
              AND cp.created_at >= :from AND cp.created_at < :cursor
              AND cp.deleted_at IS NULL AND sc.deleted_at IS NULL
            ORDER BY cp.created_at DESC
            """, nativeQuery = true)
    List<CyclePositionEntity> findByStrategyIdWithCursor(
            @Param("strategyId") UUID strategyId,
            @Param("from") Instant from,
            @Param("cursor") Instant cursor,
            Pageable pageable);

    // 시드 수정 시 당일(KST) 기존 스냅샷만 소프트 삭제
    @Modifying
    @Query(value = """
            UPDATE cycle_position cp SET deleted_at = :now
            FROM strategy_cycle sc WHERE cp.strategy_cycle_id = sc.id
            AND sc.strategy_id = :strategyId
            AND cp.created_at >= :dayStart AND cp.created_at < :dayEnd
            AND cp.deleted_at IS NULL
            """, nativeQuery = true)
    void softDeleteByStrategyIdAndDate(
            @Param("strategyId") UUID strategyId,
            @Param("dayStart") Instant dayStart,
            @Param("dayEnd") Instant dayEnd,
            @Param("now") Instant now);

    @Modifying
    @Query(value = """
            UPDATE cycle_position cp SET deleted_at = :now
            FROM strategy_cycle sc WHERE cp.strategy_cycle_id = sc.id
            AND sc.strategy_id = :strategyId AND cp.deleted_at IS NULL
            """, nativeQuery = true)
    void softDeleteByStrategyId(@Param("strategyId") UUID strategyId, @Param("now") Instant now);

    @Modifying
    @Query(value = """
            UPDATE cycle_position cp SET deleted_at = :now
            FROM strategy_cycle sc JOIN strategy s ON sc.strategy_id = s.id
            WHERE cp.strategy_cycle_id = sc.id
            AND s.account_id = :accountId AND cp.deleted_at IS NULL
            """, nativeQuery = true)
    void softDeleteByAccountId(@Param("accountId") UUID accountId, @Param("now") Instant now);

    @Modifying
    @Query(value = """
            UPDATE cycle_position cp SET deleted_at = :now
            FROM strategy_cycle sc JOIN strategy s ON sc.strategy_id = s.id
            JOIN accounts a ON s.account_id = a.id
            WHERE cp.strategy_cycle_id = sc.id
            AND a.user_id = :userId AND cp.deleted_at IS NULL
            """, nativeQuery = true)
    void softDeleteByUserId(@Param("userId") UUID userId, @Param("now") Instant now);
}
