package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.AdminAccountItem;
import com.kista.adapter.in.web.dto.AnomaliesResponse;
import com.kista.adapter.in.web.dto.AuditLogResponse;
import com.kista.adapter.in.web.dto.ErrorLogResponse;
import com.kista.domain.model.admin.AdminAnomalies;
import com.kista.domain.model.admin.AdminUserView;
import com.kista.domain.port.in.AdminQueryUseCase;
import com.kista.domain.port.in.AdminUserUseCase;
import io.swagger.v3.oas.annotations.Operation;
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

@Tag(name = "Admin", description = "관리자 API")
@RestController
@RequestMapping("/api/admin/logs")
@RequiredArgsConstructor
public class AdminObservabilityController {

    private static final int MAX_LIMIT = 500;

    private final AdminQueryUseCase adminQuery;
    private final AdminUserUseCase adminUser;

    // 감사 로그 — 관리자 액션 기록
    @Operation(summary = "감사 로그 조회", description = "관리자 액션 기록을 기간별로 조회합니다.")
    @GetMapping("/audit")
    public List<AuditLogResponse> listAuditLogs(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Instant fromInstant = toInstantOrDefault(from, 0, null);
        Instant toInstant   = toInstantOrDefault(to, 1, null);
        return adminQuery.listAuditLogs(fromInstant, toInstant).stream()
                .map(AuditLogResponse::from)
                .toList();
    }

    // 오류 로그 — 서버 예외 기록
    @Operation(summary = "오류 로그 조회", description = "서버 예외 기록을 조회합니다. limit/기간 필터링이 가능합니다.")
    @GetMapping("/errors")
    public List<ErrorLogResponse> listErrorLogs(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        int safeLimit = Math.min(limit, MAX_LIMIT);
        if (from == null && to == null) {
            return adminQuery.listErrorLogs(safeLimit).stream().map(ErrorLogResponse::from).toList();
        }
        Instant fromInstant = toInstantOrDefault(from, 0, Instant.EPOCH);
        Instant toInstant   = toInstantOrDefault(to, 1, Instant.now());
        return adminQuery.listErrorLogs(safeLimit, fromInstant, toInstant).stream()
                .map(ErrorLogResponse::from)
                .toList();
    }

    // LocalDate(KST 기준 조회 파라미터) → Instant(UTC) 변환 — null이면 defaultValue 반환
    // dayOffset: from=0(당일 자정), to=1(다음날 자정 — 당일 포함을 위한 상한 exclusive)
    private static Instant toInstantOrDefault(LocalDate date, int dayOffset, Instant defaultValue) {
        return date != null ? date.plusDays(dayOffset).atStartOfDay().toInstant(ZoneOffset.UTC) : defaultValue;
    }

    // 오류 로그 소프트 삭제 — 조치 완료 처리
    @Operation(summary = "오류 로그 소프트 삭제", description = "조치 완료 처리를 위해 오류 로그를 소프트 삭제합니다.")
    @DeleteMapping("/errors/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void softDeleteErrorLog(@PathVariable UUID id) {
        adminQuery.deleteErrorLog(id);
    }

    // 이상 징후 — 일시정지·비활성 계좌
    @Operation(summary = "이상 징후 조회", description = "일시정지·비활성 계좌 목록을 반환합니다.")
    @GetMapping("/anomalies")
    public AnomaliesResponse getAnomalies(
            @RequestParam(defaultValue = "7") int inactiveDays,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        AdminAnomalies anomalies = adminQuery.getAnomalies(inactiveDays, from, to);
        Map<UUID, AdminUserView> userMap = AdminUserViews.mapById(adminUser);

        List<AdminAccountItem> paused = anomalies.pausedAccounts().stream()
                .map(a -> AdminAccountItem.from(a, userMap))
                .toList();
        List<AdminAccountItem> inactive = anomalies.inactiveAccounts().stream()
                .map(a -> AdminAccountItem.from(a, userMap))
                .toList();

        return new AnomaliesResponse(paused, inactive);
    }

}
