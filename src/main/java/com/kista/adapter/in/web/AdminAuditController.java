package com.kista.adapter.in.web;

import com.kista.domain.model.admin.AuditLog;
import com.kista.domain.port.in.AdminQueryUseCase;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Admin", description = "관리자 API")
@RestController
@RequestMapping("/api/admin/audit-logs")
@RequiredArgsConstructor
public class AdminAuditController {

    private final AdminQueryUseCase adminQuery;

    @GetMapping
    public List<AdminAuditLogResponse> listAuditLogs() {
        return adminQuery.listAuditLogs().stream()
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
