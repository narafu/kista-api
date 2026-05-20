package com.kista.adapter.out.persistence.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

interface AuditLogJpaRepository extends JpaRepository<AuditLogEntity, UUID> {
    // 최신순 상위 100건 — Spring Data 메서드 쿼리
    List<AuditLogEntity> findTop100ByOrderByCreatedAtDesc();
}
