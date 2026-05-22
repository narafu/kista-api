package com.kista.adapter.out.persistence.strategy;

import com.kista.domain.model.strategy.Strategy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

interface StrategyJpaRepository extends JpaRepository<StrategyEntity, UUID> {

    // 계좌 ID로 전략 목록 조회 (1:N)
    List<StrategyEntity> findAllByAccountId(UUID accountId);

    // 같은 계좌에 같은 type 중복 방지
    boolean existsByAccountIdAndType(UUID accountId, Strategy.StrategyType type);

    // ACTIVE 사용자의 ACTIVE 전략 전체 조회 (스케줄러용)
    @Query(value = """
            SELECT s.* FROM strategies s
            JOIN accounts a ON s.account_id = a.id
            JOIN users u ON a.user_id = u.id
            WHERE u.status = 'ACTIVE' AND s.status = 'ACTIVE'
            """, nativeQuery = true)
    List<StrategyEntity> findAllActiveStrategies();
}
