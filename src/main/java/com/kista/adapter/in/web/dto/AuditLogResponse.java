package com.kista.adapter.in.web.dto;

import com.kista.domain.model.admin.AuditLog;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

// 감사 로그 응답 DTO — 관리자 액션 기록 조회
public record AuditLogResponse(
        UUID id,
        UUID adminId,
        String action,
        String targetType,
        UUID targetId,
        Map<String, Object> payload,
        Instant createdAt
) {
    public static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(
                log.id(), log.adminId(), log.action(), log.targetType(),
                log.targetId(), log.payload(), log.createdAt());
    }
}
