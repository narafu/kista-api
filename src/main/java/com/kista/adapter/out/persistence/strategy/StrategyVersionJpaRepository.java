package com.kista.adapter.out.persistence.strategy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface StrategyVersionJpaRepository extends JpaRepository<StrategyVersionEntity, UUID> {

    Optional<StrategyVersionEntity> findTop1ByStrategyIdAndDeletedAtIsNullOrderByVersionNoDesc(UUID strategyId);

    @Query("""
            SELECT COALESCE(MAX(sv.versionNo), 0)
            FROM StrategyVersionEntity sv
            WHERE sv.strategyId = :strategyId
            """)
    int findMaxVersionNoByStrategyId(@Param("strategyId") UUID strategyId);

    @Modifying
    @Query("UPDATE StrategyVersionEntity sv SET sv.deletedAt = :now WHERE sv.strategyId = :strategyId AND sv.deletedAt IS NULL")
    void softDeleteActiveByStrategyId(@Param("strategyId") UUID strategyId, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE StrategyVersionEntity sv SET sv.deletedAt = :now WHERE sv.strategyId = :strategyId AND sv.deletedAt IS NULL")
    void softDeleteByStrategyId(@Param("strategyId") UUID strategyId, @Param("now") Instant now);
}
