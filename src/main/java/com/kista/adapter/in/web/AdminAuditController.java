package com.kista.adapter.in.web;

import com.kista.domain.model.AuditLog;
import com.kista.domain.port.in.AdminListAuditLogsUseCase;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Admin - Audit", description = "관리자 감사 로그 API")
@RestController
@RequestMapping("/api/admin/audit-logs")
@RequiredArgsConstructor
public class AdminAuditController {

    private final AdminListAuditLogsUseCase listAuditLogs;

    @GetMapping
    public List<AdminAuditLogResponse> listAuditLogs() {
        return listAuditLogs.listAll().stream()
                .map(AdminAuditLogResponse::from)
                .toList();
    }

    // 감사 로그 응답 DTO — payload는 Map으로 nested JSON 직렬화
    record AdminAuditLogResponse(
            UUID id,
            UUID adminId,
            String action,      // "USER_APPROVE" | "USER_REJECT" | "USER_ROLE_CHANGE" | "USER_DELETE"
            String targetType,  // "USER"
            UUID targetId,
            Map<String, Object> payload, // JSONB → nested JSON (null 허용)
            Instant createdAt
    ) {
        static AdminAuditLogResponse from(AuditLog log) {
            return new AdminAuditLogResponse(
                    log.id(), log.adminId(), log.action(), log.targetType(),
                    log.targetId(), log.payload(), log.createdAt());
        }
    }
}
