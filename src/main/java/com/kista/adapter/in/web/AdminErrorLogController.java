package com.kista.adapter.in.web;

import com.kista.domain.model.admin.AppErrorLog;
import com.kista.domain.port.out.AppErrorLogPort;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Admin", description = "관리자 API")
@RestController
@RequestMapping("/api/admin/error-logs")
@RequiredArgsConstructor
public class AdminErrorLogController {

    private static final int MAX_LIMIT = 500;

    private final AppErrorLogPort appErrorLogPort;

    @GetMapping
    public List<AppErrorLogResponse> listErrorLogs(
            @RequestParam(defaultValue = "100") int limit) {
        // limit 상한 500건 강제
        int safeLimit = Math.min(limit, MAX_LIMIT);
        return appErrorLogPort.findRecent(safeLimit).stream()
                .map(AppErrorLogResponse::from)
                .toList();
    }

    record AppErrorLogResponse(
            UUID id,
            String errorType,   // 예외 클래스 단순명
            String message,     // e.getMessage()
            String stackTrace,  // 전체 스택트레이스
            Map<String, String> context, // 발생 위치 메타
            Instant createdAt
    ) {
        static AppErrorLogResponse from(AppErrorLog log) {
            return new AppErrorLogResponse(
                    log.id(), log.errorType(), log.message(),
                    log.stackTrace(), log.context(), log.createdAt()
            );
        }
    }
}
