package com.kista.admin.adapter.out.persistence.audit;

import com.kista.admin.domain.model.AuditLog;
import com.kista.admin.application.port.output.AuditLogPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE) // AuditLogJpaRepository가 package-private
class AuditLogPersistenceAdapter implements AuditLogPort {

    private final AuditLogJpaRepository repo; // audit_logs 테이블 JPA 저장소

    @Override
    public void log(UUID adminId, String action, String targetType, UUID targetId, Map<String, Object> payload) {
        // 엔티티 생성 후 저장 (id·createdAt은 DB 자동 부여, payload는 Hibernate가 jsonb로 직접 매핑)
        AuditLogEntity entity = new AuditLogEntity(null, adminId, action, targetType, targetId, payload);
        repo.save(entity);
    }

    @Override
    public AuditLog findById(UUID id) {
        return repo.findById(id).map(this::toDomain).orElseThrow(() -> new NoSuchElementException("AuditLog not found: " + id));
    }

    @Override
    public List<AuditLog> findAll() {
        // 최신순 상위 100건 조회 후 도메인 변환
        return repo.findTop100ByOrderByCreatedAtDesc().stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<AuditLog> findAll(Instant from, Instant to) {
        return repo.findTop100ByCreatedAtBetween(from, to).stream()
                .map(this::toDomain)
                .toList();
    }

    // 엔티티 → 도메인 record 변환 (payload는 Hibernate가 이미 Map으로 매핑)
    private AuditLog toDomain(AuditLogEntity entity) {
        return new AuditLog(entity.getId(), entity.getAdminId(), entity.getAction(), entity.getTargetType(), entity.getTargetId(), entity.getPayload(), entity.getCreatedAt());
    }
}
