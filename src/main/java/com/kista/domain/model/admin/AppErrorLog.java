package com.kista.domain.model.admin;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AppErrorLog(
    UUID id,
    String errorType,    // 예외 클래스 단순명
    String message,      // e.getMessage()
    String stackTrace,   // 전체 스택트레이스
    Map<String, String> context, // 발생 위치 메타
    Instant createdAt
) {}
