package com.kista.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

interface AuditLogJpaRepository extends JpaRepository<AuditLogEntity, UUID> {
}
