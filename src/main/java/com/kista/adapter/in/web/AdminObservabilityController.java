package com.kista.adapter.in.web;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.admin.AdminAnomalies;
import com.kista.domain.model.admin.AdminUserView;
import com.kista.domain.model.admin.AppErrorLog;
import com.kista.domain.model.admin.AuditLog;
import com.kista.domain.port.in.AdminQueryUseCase;
import com.kista.domain.port.in.AdminUserUseCase;
import com.kista.domain.port.out.AppErrorLogPort;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Tag(name = "Admin", description = "관리자 API")
@RestController
@RequestMapping("/api/admin/logs")
@RequiredArgsConstructor
public class AdminObservabilityController {

    private static final int MAX_LIMIT = 500;

    private final AdminQueryUseCase adminQuery;
    private final AdminUserUseCase adminUser;
    private final AppErrorLogPort appErrorLogPort;

    // 감사 로그 — 관리자 액션 기록
    @GetMapping("/audit")
    public List<AuditLogResponse> listAuditLogs() {
        return adminQuery.listAuditLogs().stream()
                .map(AuditLogResponse::from)
                .toList();
    }

    // 오류 로그 — 서버 예외 기록
    @GetMapping("/errors")
    public List<ErrorLogResponse> listErrorLogs(
            @RequestParam(defaultValue = "100") int limit) {
        int safeLimit = Math.min(limit, MAX_LIMIT);
        return appErrorLogPort.findRecent(safeLimit).stream()
                .map(ErrorLogResponse::from)
                .toList();
    }

    // 이상 징후 — 일시정지·비활성 계좌
    @GetMapping("/anomalies")
    public AnomaliesResponse getAnomalies() {
        AdminAnomalies anomalies = adminQuery.getAnomalies();
        Map<UUID, AdminUserView> userMap = adminUser.listAll().stream()
                .collect(Collectors.toMap(AdminUserView::id, Function.identity()));

        List<AccountItem> paused = anomalies.pausedAccounts().stream()
                .map(a -> AccountItem.from(a, userMap))
                .toList();
        List<AccountItem> inactive = anomalies.inactiveAccounts().stream()
                .map(a -> AccountItem.from(a, userMap))
                .toList();

        return new AnomaliesResponse(paused, inactive);
    }

    // DTO records
    record AuditLogResponse(
            UUID id,
            UUID adminId,
            String action,
            String targetType,
            UUID targetId,
            Map<String, Object> payload,
            Instant createdAt
    ) {
        static AuditLogResponse from(AuditLog log) {
            return new AuditLogResponse(
                    log.id(), log.adminId(), log.action(), log.targetType(),
                    log.targetId(), log.payload(), log.createdAt());
        }
    }

    record ErrorLogResponse(
            UUID id,
            String errorType,
            String message,
            String stackTrace,
            Map<String, String> context,
            Instant createdAt
    ) {
        static ErrorLogResponse from(AppErrorLog log) {
            return new ErrorLogResponse(
                    log.id(), log.errorType(), log.message(),
                    log.stackTrace(), log.context(), log.createdAt());
        }
    }

    record AnomaliesResponse(
            List<AccountItem> pausedAccounts,
            List<AccountItem> inactiveAccounts
    ) {}

    record AccountItem(
            UUID id,
            UUID userId,
            String ownerNickname,
            String accountNoMasked,
            String broker
    ) {
        static AccountItem from(Account a, Map<UUID, AdminUserView> userMap) {
            AdminUserView user = a.userId() != null ? userMap.get(a.userId()) : null;
            String nickname = user != null ? user.nickname() : "(알 수 없음)";
            String masked = a.accountNo() != null
                    ? "****" + a.accountNo().substring(Math.max(0, a.accountNo().length() - 4))
                    : "****";
            return new AccountItem(
                    a.id(), a.userId(), nickname, masked,
                    a.broker() != null ? a.broker().name() : null);
        }
    }
}
