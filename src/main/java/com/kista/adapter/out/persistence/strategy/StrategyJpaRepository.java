package com.kista.adapter.out.persistence.strategy;

import com.kista.domain.model.strategy.Strategy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface StrategyJpaRepository extends JpaRepository<StrategyEntity, UUID> {

    // 계좌 ID로 전략 목록 조회 (1:N, @SQLRestriction 자동 적용)
    List<StrategyEntity> findAllByAccountId(UUID accountId);

    // 같은 계좌에 같은 종목 중복 방지 (체결 귀속을 위해 계좌 내 종목 유니크)
    boolean existsByAccountIdAndTicker(UUID accountId, Strategy.Ticker ticker);

    // ACTIVE 사용자의 ACTIVE 전략 전체 조회 (스케쥴러용) — 소프트 삭제 행 명시적 제외
    @Query(value = """
            SELECT s.* FROM strategy s
            JOIN accounts a ON s.account_id = a.id
            JOIN users u ON a.user_id = u.id
            WHERE u.status = 'ACTIVE' AND s.status = 'ACTIVE'
              AND s.deleted_at IS NULL AND a.deleted_at IS NULL AND u.deleted_at IS NULL
            """, nativeQuery = true)
    List<StrategyEntity> findAllActiveStrategies();

    @Modifying
    @Query("UPDATE StrategyEntity s SET s.deletedAt = :now WHERE s.id = :id")
    void softDeleteById(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE StrategyEntity s SET s.deletedAt = :now WHERE s.accountId = :accountId AND s.deletedAt IS NULL")
    void softDeleteByAccountId(@Param("accountId") UUID accountId, @Param("now") Instant now);

    // 사용자 계좌에 속한 모든 전략 소프트 삭제 (계정 탈퇴 cascade)
    @Modifying
    @Query(value = """
            UPDATE strategy s SET deleted_at = :now
            FROM accounts a WHERE s.account_id = a.id
            AND a.user_id = :userId AND s.deleted_at IS NULL
            """, nativeQuery = true)
    void softDeleteByUserId(@Param("userId") UUID userId, @Param("now") Instant now);
}
