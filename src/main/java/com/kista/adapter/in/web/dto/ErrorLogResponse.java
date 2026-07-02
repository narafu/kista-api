package com.kista.adapter.in.web.dto;

import com.kista.domain.model.admin.AppErrorLog;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

// 서버 오류 로그 응답 DTO
public record ErrorLogResponse(
        UUID id,
        String errorType,
        String message,
        String stackTrace,
        Map<String, String> context,
        Instant createdAt
) {
    public static ErrorLogResponse from(AppErrorLog log) {
        return new ErrorLogResponse(
                log.id(), log.errorType(), log.message(),
                log.stackTrace(), log.context(), log.createdAt());
    }
}
