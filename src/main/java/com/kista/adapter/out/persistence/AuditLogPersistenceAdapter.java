package com.kista.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kista.domain.model.AuditLog;
import com.kista.domain.port.out.AuditLogPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = lombok.AccessLevel.PACKAGE) // AuditLogJpaRepository가 package-private
class AuditLogPersistenceAdapter implements AuditLogPort {

    private final AuditLogJpaRepository repo;
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
        return repo.findById(id).map(this::toDomain).orElseThrow();
    }

    // 엔티티 → 도메인 record 변환 (payload JSON String → Map 역직렬화)
    private AuditLog toDomain(AuditLogEntity e) {
        Map<String, Object> p = null;
        if (e.getPayload() != null) {
            try {
                p = objectMapper.readValue(e.getPayload(), new TypeReference<>() {});
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("payload 역직렬화 실패", ex);
            }
        }
        return new AuditLog(e.getId(), e.getAdminId(), e.getAction(), e.getTargetType(), e.getTargetId(), p, e.getCreatedAt());
    }
}
