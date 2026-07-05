package com.kista.adapter.out.persistence.strategy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

// strategy_cycle_vr 테이블 — deleted_at 없음, PK=strategyCycleId 단순 조회/저장
interface StrategyCycleVrJpaRepository extends JpaRepository<StrategyCycleVrEntity, UUID> {
}
