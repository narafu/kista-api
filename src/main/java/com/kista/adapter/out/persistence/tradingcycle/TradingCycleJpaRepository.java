package com.kista.adapter.out.persistence.tradingcycle;

import com.kista.domain.model.tradingcycle.TradingCycle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface TradingCycleJpaRepository extends JpaRepository<TradingCycleEntity, UUID> {

    // 계좌 ID로 사이클 목록 조회 (1:N)
    List<TradingCycleEntity> findAllByAccountId(UUID accountId);

    // 같은 계좌에 같은 type 중복 방지
    boolean existsByAccountIdAndType(UUID accountId, TradingCycle.Type type);

    // ACTIVE 사용자의 ACTIVE 사이클 전체 조회 (스케줄러용) — 소프트 삭제된 행 명시적 제외
    @Query(value = """
            SELECT tc.* FROM trading_cycle tc
            JOIN accounts a ON tc.account_id = a.id
            JOIN users u ON a.user_id = u.id
            WHERE u.status = 'ACTIVE' AND tc.status = 'ACTIVE'
              AND tc.deleted_at IS NULL AND a.deleted_at IS NULL AND u.deleted_at IS NULL
            """, nativeQuery = true)
    List<TradingCycleEntity> findAllActiveCycles();

    @Modifying
    @Query("UPDATE TradingCycleEntity tc SET tc.deletedAt = :now WHERE tc.id = :id")
    void softDeleteById(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE TradingCycleEntity tc SET tc.deletedAt = :now WHERE tc.accountId = :accountId AND tc.deletedAt IS NULL")
    void softDeleteByAccountId(@Param("accountId") UUID accountId, @Param("now") Instant now);

    // 사용자 계좌에 속한 모든 사이클 소프트 삭제 (계정 탈퇴 cascade)
    @Modifying
    @Query(value = """
            UPDATE trading_cycle tc SET deleted_at = :now
            FROM accounts a WHERE tc.account_id = a.id
            AND a.user_id = :userId AND tc.deleted_at IS NULL
            """, nativeQuery = true)
    void softDeleteByUserId(@Param("userId") UUID userId, @Param("now") Instant now);
}
