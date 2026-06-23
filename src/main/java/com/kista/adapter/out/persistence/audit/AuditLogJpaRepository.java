package com.kista.adapter.out.persistence.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface AuditLogJpaRepository extends JpaRepository<AuditLogEntity, UUID> {
    // 최신순 상위 100건 — Spring Data 메서드 쿼리
    List<AuditLogEntity> findTop100ByOrderByCreatedAtDesc();

    // 기간 범위 조회 (최신순, 최대 100건)
    @Query("SELECT a FROM AuditLogEntity a WHERE a.createdAt >= :from AND a.createdAt < :to ORDER BY a.createdAt DESC LIMIT 100")
    List<AuditLogEntity> findTop100ByCreatedAtBetween(@Param("from") Instant from, @Param("to") Instant to);
}
