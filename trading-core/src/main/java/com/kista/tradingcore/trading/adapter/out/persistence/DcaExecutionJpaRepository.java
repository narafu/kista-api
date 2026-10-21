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

    List<DcaExecutionEntity> findByStrategyIdOrderByTradeDateDesc(UUID strategyId, Pageable pageable);

    long countByStrategyId(UUID strategyId);

    // 전략×규칙별 최신 1건
    @Query(value = """
            SELECT DISTINCT ON (strategy_id, rule_key) * FROM dca_execution
            WHERE strategy_id IN (:strategyIds)
            ORDER BY strategy_id, rule_key, trade_date DESC
            """, nativeQuery = true)
    List<DcaExecutionEntity> findLatestByStrategyIds(@Param("strategyIds") Collection<UUID> strategyIds);

    // UNIQUE(strategy_id, rule_key, trade_date) 충돌 시 무시 — 반환값 1이면 삽입
    @Modifying
    @Query(value = """
            INSERT INTO dca_execution (id, strategy_id, rule_key, trade_date, status, skip_reason, order_id, quantity, reference_price, detail)
            VALUES (:id, :strategyId, :ruleKey, :tradeDate, :status, :skipReason, :orderId, :quantity, :referencePrice, :detail)
            ON CONFLICT (strategy_id, rule_key, trade_date) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoringConflict(@Param("id") UUID id, @Param("strategyId") UUID strategyId, @Param("ruleKey") String ruleKey,
                               @Param("tradeDate") LocalDate tradeDate,
                               @Param("status") String status, @Param("skipReason") String skipReason, @Param("orderId") UUID orderId,
                               @Param("quantity") BigDecimal quantity, @Param("referencePrice") BigDecimal referencePrice,
                               @Param("detail") String detail);
}
