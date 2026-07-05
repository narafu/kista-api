package com.kista.adapter.out.persistence.strategy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

// strategy_vr_version 테이블 — deleted_at 없음, 부모 strategy_version soft-delete로 배제
interface StrategyVrVersionJpaRepository extends JpaRepository<StrategyVrVersionEntity, UUID> {

    // 전략의 활성 버전(strategy_version.deleted_at IS NULL) 중 최신 VR 상세 조회
    // nativeQuery는 @SQLRestriction 미적용 — sv.deleted_at IS NULL 수동 명시
    @Query(value = """
            SELECT svv.* FROM strategy_vr_version svv
            JOIN strategy_version sv ON svv.strategy_version_id = sv.id
            WHERE sv.strategy_id = :strategyId
              AND sv.deleted_at IS NULL
            ORDER BY svv.created_at DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<StrategyVrVersionEntity> findActiveByStrategyId(@Param("strategyId") UUID strategyId);
}
