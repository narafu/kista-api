package com.kista.adapter.out.persistence.tradingcycle;

import com.kista.domain.model.tradingcycle.TradingCycle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

interface TradingCycleJpaRepository extends JpaRepository<TradingCycleEntity, UUID> {

    // 계좌 ID로 사이클 목록 조회 (1:N)
    List<TradingCycleEntity> findAllByAccountId(UUID accountId);

    // 같은 계좌에 같은 type 중복 방지
    boolean existsByAccountIdAndType(UUID accountId, TradingCycle.Type type);

    // ACTIVE 사용자의 ACTIVE 사이클 전체 조회 (스케줄러용)
    @Query(value = """
            SELECT tc.* FROM trading_cycle tc
            JOIN accounts a ON tc.account_id = a.id
            JOIN users u ON a.user_id = u.id
            WHERE u.status = 'ACTIVE' AND tc.status = 'ACTIVE'
            """, nativeQuery = true)
    List<TradingCycleEntity> findAllActiveCycles();
}
