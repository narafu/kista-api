package com.kista.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kista.domain.model.AuditLog;
import com.kista.domain.port.out.AuditLogPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE) // AuditLogJpaRepository가 package-private
class AuditLogPersistenceAdapter implements AuditLogPort {

    private final AuditLogJpaRepository repo; // audit_logs 테이블 JPA 저장소
    private final ObjectMapper objectMapper; // Spring Boot 자동 구성 빈

    @Override
    public void log(UUID adminId, String action, String targetType, UUID targetId, Map<String, Object> payload) {
        // payload Map → JSON String 직렬화
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("payload 직렬화 실패", e);
        }
        // 엔티티 생성 후 저장 (id·createdAt은 DB 자동 부여)
        AuditLogEntity entity = new AuditLogEntity(null, adminId, action, targetType, targetId, payloadJson, null);
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

    // 엔티티 → 도메인 record 변환 (payload JSON String → Map 역직렬화)
    private AuditLog toDomain(AuditLogEntity entity) {
        Map<String, Object> p = null;
        if (entity.getPayload() != null) {
            try {
                p = objectMapper.readValue(entity.getPayload(), new TypeReference<>() {});
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("payload 역직렬화 실패", ex);
            }
        }
        return new AuditLog(entity.getId(), entity.getAdminId(), entity.getAction(), entity.getTargetType(), entity.getTargetId(), p, entity.getCreatedAt());
    }
}
