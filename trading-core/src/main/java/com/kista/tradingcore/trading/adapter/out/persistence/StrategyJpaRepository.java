package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.tradingcore.trading.domain.model.Strategy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import com.kista.shared.kernel.StrategyTicker;

interface StrategyJpaRepository extends JpaRepository<StrategyEntity, UUID> {

    interface CycleStrategySummaryProjection {
        UUID getCycleId();
        UUID getStrategyId();
        String getStrategyType();
    }

    @Query(value = """
            SELECT sc.id AS cycleId, s.id AS strategyId, s.type AS strategyType
            FROM strategy_cycle sc
            JOIN strategy s ON sc.strategy_id = s.id
            WHERE sc.id IN :cycleIds
              AND sc.deleted_at IS NULL AND s.deleted_at IS NULL
            """, nativeQuery = true)
    List<CycleStrategySummaryProjection> findStrategySummariesByCycleIds(@Param("cycleIds") Collection<UUID> cycleIds);

    // 계좌 ID로 전략 목록 조회 (1:N, @SQLRestriction 자동 적용)
    List<StrategyEntity> findAllByAccountId(UUID accountId);

    // 여러 계좌 ID 일괄 조회 (관리자 계좌 목록 전략 표시용)
    List<StrategyEntity> findAllByAccountIdIn(Collection<UUID> accountIds);

    // 같은 계좌·종목의 전략 조회 (@SQLRestriction이 soft delete 제외)
    List<StrategyEntity> findAllByAccountIdAndTicker(UUID accountId, StrategyTicker ticker);

    // ACTIVE 사용자의 ACTIVE 전략 전체 조회 (스케쥴러용) — 소프트 삭제 행 명시적 제외
    // 사용자 활성 여부는 trading 소유 복제본(user_notify_profile.is_active)만 본다 — root 소유 users 비의존
    @Query(value = """
            SELECT s.* FROM strategy s
            JOIN accounts a ON s.account_id = a.id
            JOIN user_notify_profile p ON p.user_id = a.user_id
            WHERE p.is_active AND s.status = 'ACTIVE'
              AND s.deleted_at IS NULL AND a.deleted_at IS NULL
            """, nativeQuery = true)
    List<StrategyEntity> findAllActiveStrategies();

    // 정지 전략 중 진행 중 사이클(end_date null)에 당일 접수 주문(상태 무관)이 있는 전략 — 마감 배치가 체결을 원장에 반영하는 리포트 전용
    @Query(value = """
            SELECT s.* FROM strategy s
            JOIN accounts a ON s.account_id = a.id
            JOIN user_notify_profile p ON p.user_id = a.user_id
            WHERE p.is_active AND s.status = 'PAUSED'
              AND s.deleted_at IS NULL AND a.deleted_at IS NULL
              AND EXISTS (SELECT 1 FROM orders o JOIN strategy_cycle sc ON o.strategy_cycle_id = sc.id
                          WHERE sc.strategy_id = s.id AND sc.deleted_at IS NULL AND sc.end_date IS NULL
                            AND o.trade_date = :tradeDate AND o.external_order_id IS NOT NULL
                            AND o.order_leg <> :unknownLeg)
            """, nativeQuery = true)
    List<StrategyEntity> findPausedWithReportableOrders(@Param("tradeDate") LocalDate tradeDate, @Param("unknownLeg") String unknownLeg);

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
