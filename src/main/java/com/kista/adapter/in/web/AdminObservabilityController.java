package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.AdminAccountItem;
import com.kista.adapter.in.web.dto.AnomaliesResponse;
import com.kista.adapter.in.web.dto.AuditLogResponse;
import com.kista.adapter.in.web.dto.ErrorLogResponse;
import com.kista.domain.model.admin.AdminAnomalies;
import com.kista.domain.model.admin.AdminUserView;
import com.kista.domain.model.admin.AppErrorLog;
import com.kista.domain.model.admin.AuditLog;
import com.kista.domain.port.in.AdminQueryUseCase;
import com.kista.domain.port.in.AdminUserUseCase;
import com.kista.domain.port.out.AppErrorLogPort;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;


import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
    public List<AuditLogResponse> listAuditLogs(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Instant fromInstant = from != null ? from.atStartOfDay().toInstant(ZoneOffset.UTC) : null;
        Instant toInstant   = to   != null ? to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC) : null;
        return adminQuery.listAuditLogs(fromInstant, toInstant).stream()
                .map(AuditLogResponse::from)
                .toList();
    }

    // 오류 로그 — 서버 예외 기록
    @GetMapping("/errors")
    public List<ErrorLogResponse> listErrorLogs(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        int safeLimit = Math.min(limit, MAX_LIMIT);
        if (from == null && to == null) {
            return appErrorLogPort.findRecent(safeLimit).stream().map(ErrorLogResponse::from).toList();
        }
        Instant fromInstant = from != null ? from.atStartOfDay().toInstant(ZoneOffset.UTC) : Instant.EPOCH;
        Instant toInstant   = to   != null ? to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC) : Instant.now();
        return appErrorLogPort.findRecent(safeLimit, fromInstant, toInstant).stream()
                .map(ErrorLogResponse::from)
                .toList();
    }

    // 오류 로그 소프트 삭제 — 조치 완료 처리
    @DeleteMapping("/errors/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void softDeleteErrorLog(@PathVariable UUID id) {
        appErrorLogPort.softDelete(id);
    }

    // 이상 징후 — 일시정지·비활성 계좌
    @GetMapping("/anomalies")
    public AnomaliesResponse getAnomalies(
            @RequestParam(defaultValue = "7") int inactiveDays,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        AdminAnomalies anomalies = adminQuery.getAnomalies(inactiveDays, from, to);
        Map<UUID, AdminUserView> userMap = adminUser.listAll(null, null).stream()
                .collect(Collectors.toMap(AdminUserView::id, Function.identity()));

        List<AdminAccountItem> paused = anomalies.pausedAccounts().stream()
                .map(a -> AdminAccountItem.from(a, userMap))
                .toList();
        List<AdminAccountItem> inactive = anomalies.inactiveAccounts().stream()
                .map(a -> AdminAccountItem.from(a, userMap))
                .toList();

        return new AnomaliesResponse(paused, inactive);
    }

}
