package com.kista.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

// kista-ui 오류 바운더리(error.tsx/global-error.tsx)가 보고하는 클라이언트 오류 요청 body
public record ClientErrorLogRequest(
        @Schema(description = "오류 유형 (error.name)")
        @NotBlank @Size(max = 255) String errorType,
        @Schema(description = "오류 메시지")
        @Size(max = 2000) String message,
        @Schema(description = "스택트레이스")
        @Size(max = 8000) String stackTrace,
        @Schema(description = "발생 위치 메타 (pathname, digest 등)")
        Map<String, String> context) {}
