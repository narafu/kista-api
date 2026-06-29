package com.kista.adapter.out.persistence.strategy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface StrategyInfiniteJpaRepository extends JpaRepository<StrategyInfiniteEntity, UUID> {

    void deleteByStrategyId(UUID strategyId);
}
