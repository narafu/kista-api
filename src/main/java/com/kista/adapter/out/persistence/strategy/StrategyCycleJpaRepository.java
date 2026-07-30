package com.kista.adapter.out.persistence.strategy;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface StrategyCycleJpaRepository extends JpaRepository<StrategyCycleEntity, UUID> {

    // 사이클 단위 동시 실행 직렬화용 row lock — 트랜잭션 종료까지 보유 (BuyOrderPriceCapper 등)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT sc FROM StrategyCycleEntity sc WHERE sc.id = :id")
    Optional<StrategyCycleEntity> lockById(@Param("id") UUID id);

    // 전략의 현재 사이클 — deleted_at IS NULL(@SQLRestriction) 중 createdAt 최신 1건
    Optional<StrategyCycleEntity> findTop1ByStrategyIdOrderByCreatedAtDesc(UUID strategyId);

    // 전략의 최초 사이클 — deleted_at IS NULL(@SQLRestriction) 중 createdAt 가장 오래된 1건
    Optional<StrategyCycleEntity> findTop1ByStrategyIdOrderByCreatedAtAsc(UUID strategyId);

    // 여러 전략의 전체 사이클 배치 조회 (통계용) — deleted 제외, createdAt 오름차순
    List<StrategyCycleEntity> findByStrategyIdInAndDeletedAtIsNullOrderByCreatedAtAsc(
            Collection<UUID> strategyIds);

    // 여러 전략의 현재 사이클 배치 조회 (목록 조회 N+1 방지) — strategy_id별 createdAt 최신 1건 (id DESC tie-break)
    @Query(value = """
            SELECT DISTINCT ON (strategy_id) *
            FROM strategy_cycle
            WHERE strategy_id IN (:strategyIds) AND deleted_at IS NULL
            ORDER BY strategy_id, created_at DESC, id DESC
            """, nativeQuery = true)
    List<StrategyCycleEntity> findLatestByStrategyIdIn(@Param("strategyIds") Collection<UUID> strategyIds);

    @Modifying
    @Query("UPDATE StrategyCycleEntity sc SET sc.deletedAt = :now WHERE sc.strategyId = :strategyId AND sc.deletedAt IS NULL")
    void softDeleteByStrategyId(@Param("strategyId") UUID strategyId, @Param("now") Instant now);

    // 계좌에 속한 모든 전략의 사이클 소프트 삭제
    @Modifying
    @Query(value = """
            UPDATE strategy_cycle sc SET deleted_at = :now
            FROM strategy s WHERE sc.strategy_id = s.id
            AND s.account_id = :accountId AND sc.deleted_at IS NULL
            """, nativeQuery = true)
    void softDeleteByAccountId(@Param("accountId") UUID accountId, @Param("now") Instant now);

    // 사용자 소유 모든 사이클 소프트 삭제
    @Modifying
    @Query(value = """
            UPDATE strategy_cycle sc SET deleted_at = :now
            FROM strategy s JOIN accounts a ON s.account_id = a.id
            WHERE sc.strategy_id = s.id
            AND a.user_id = :userId AND sc.deleted_at IS NULL
            """, nativeQuery = true)
    void softDeleteByUserId(@Param("userId") UUID userId, @Param("now") Instant now);
}
