package com.kista.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface StrategyJpaRepository extends JpaRepository<StrategyEntity, UUID> {

    // 계좌 ID로 전략 조회 (현재 1:1 — 향후 1:N 전환 시 findAllByAccountId로 교체)
    Optional<StrategyEntity> findByAccountId(UUID accountId);
}
