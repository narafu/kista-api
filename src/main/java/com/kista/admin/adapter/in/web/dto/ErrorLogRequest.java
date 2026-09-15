package com.kista.admin.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

// trading-core TradingExceptionHandler가 KisApiException/TossApiException 발생 시 보고하는 내부 오류 리포트 body
// AppErrorLogPort.save(String, String, String, Map)가 실제로 받는 필드만 담는다 — Exception 객체 자체는 HTTP로 넘길 수 없음
public record ErrorLogRequest(
        @Schema(description = "오류 유형 (예외 클래스 simple name)")
        String errorType,
        @Schema(description = "오류 메시지")
        String message,
        @Schema(description = "스택트레이스")
        String stackTrace,
        @Schema(description = "발생 위치 메타 (caller 등)")
        Map<String, String> context) {}
