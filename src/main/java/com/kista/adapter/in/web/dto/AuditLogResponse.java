package com.kista.adapter.in.web.dto;

import com.kista.domain.model.admin.AuditLog;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

// 감사 로그 응답 DTO — 관리자 액션 기록 조회
public record AuditLogResponse(
        @Schema(description = "감사 로그 고유 ID")
        UUID id,
        @Schema(description = "액션을 수행한 관리자 사용자 ID")
        UUID adminId,
        @Schema(description = "액션 유형", example = "RUNTIME_SETTINGS_UPDATE")
        String action,
        @Schema(description = "액션 대상 리소스 종류")
        String targetType,
        @Schema(description = "액션 대상 리소스 ID")
        UUID targetId,
        @Schema(description = "액션 상세 데이터")
        Map<String, Object> payload,
        @Schema(description = "기록 일시")
        Instant createdAt
) {
    public static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(
                log.id(), log.adminId(), log.action(), log.targetType(),
                log.targetId(), log.payload(), log.createdAt());
    }
}
