package com.kista.adapter.in.web.dto;

import com.kista.domain.model.admin.AppErrorLog;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

// 서버 오류 로그 응답 DTO
public record ErrorLogResponse(
        @Schema(description = "오류 로그 고유 ID")
        UUID id,
        @Schema(description = "예외 클래스 단순명")
        String errorType,
        @Schema(description = "예외 메시지")
        String message,
        @Schema(description = "전체 스택트레이스")
        String stackTrace,
        @Schema(description = "발생 위치 메타데이터")
        Map<String, String> context,
        @Schema(description = "기록 일시")
        Instant createdAt
) {
    public static ErrorLogResponse from(AppErrorLog log) {
        return new ErrorLogResponse(
                log.id(), log.errorType(), log.message(),
                log.stackTrace(), log.context(), log.createdAt());
    }
}
