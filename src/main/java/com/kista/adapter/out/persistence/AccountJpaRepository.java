package com.kista.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

interface AccountJpaRepository extends JpaRepository<AccountEntity, UUID> {

    List<AccountEntity> findByUserId(UUID userId);

    int countByUserId(UUID userId);

    // ACTIVE 사용자의 ACTIVE 계좌 전체 조회 (스케줄러용)
    @Query(value = """
            SELECT a.* FROM accounts a
            JOIN users u ON a.user_id = u.id
            WHERE u.status = 'ACTIVE' AND a.strategy_status = 'ACTIVE'
            """, nativeQuery = true)
    List<AccountEntity> findAllActive();
}
