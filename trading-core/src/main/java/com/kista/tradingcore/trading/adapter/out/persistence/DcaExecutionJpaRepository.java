package com.kista.tradingcore.trading.adapter.out.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface DcaExecutionJpaRepository extends JpaRepository<DcaExecutionEntity, UUID> {

    Optional<DcaExecutionEntity> findByStrategyIdAndRuleKeyAndTradeDate(UUID strategyId, String ruleKey, LocalDate tradeDate);

    // 같은 거래일 다중 규칙 행의 페이지 경계 안정화 — id(UUIDv7) 2차 정렬
    List<DcaExecutionEntity> findByStrategyIdOrderByTradeDateDescIdDesc(UUID strategyId, Pageable pageable);

    long countByStrategyId(UUID strategyId);

    // 전략×규칙별 최신 1건
    @Query(value = """
            SELECT DISTINCT ON (strategy_id, rule_key) * FROM dca_execution
            WHERE strategy_id IN (:strategyIds)
            ORDER BY strategy_id, rule_key, trade_date DESC
            """, nativeQuery = true)
    List<DcaExecutionEntity> findLatestByStrategyIds(@Param("strategyIds") Collection<UUID> strategyIds);

    // ORDERED → SKIPPED 조건부 전이 — 반환값 1이면 전이
    @Modifying
    @Query(value = """
            UPDATE dca_execution SET status = 'SKIPPED', skip_reason = :skipReason, detail = :detail
            WHERE strategy_id = :strategyId AND rule_key = :ruleKey AND trade_date = :tradeDate
              AND status = 'ORDERED' AND order_id = :orderId
            """, nativeQuery = true)
    int markOrderedSkipped(@Param("strategyId") UUID strategyId, @Param("ruleKey") String ruleKey, @Param("tradeDate") LocalDate tradeDate,
                           @Param("orderId") UUID orderId, @Param("skipReason") String skipReason, @Param("detail") String detail);

    // UNIQUE(strategy_id, rule_key, trade_date) 충돌 시 무시 — 반환값 1이면 삽입
    @Modifying
    @Query(value = """
            INSERT INTO dca_execution (id, strategy_id, rule_key, direction, trade_date, status, skip_reason, order_id, quantity, reference_price, detail)
            VALUES (:id, :strategyId, :ruleKey, :direction, :tradeDate, :status, :skipReason, :orderId, :quantity, :referencePrice, :detail)
            ON CONFLICT (strategy_id, rule_key, trade_date) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoringConflict(@Param("id") UUID id, @Param("strategyId") UUID strategyId, @Param("ruleKey") String ruleKey, @Param("direction") String direction,
                               @Param("tradeDate") LocalDate tradeDate,
                               @Param("status") String status, @Param("skipReason") String skipReason, @Param("orderId") UUID orderId,
                               @Param("quantity") BigDecimal quantity, @Param("referencePrice") BigDecimal referencePrice,
                               @Param("detail") String detail);
}
