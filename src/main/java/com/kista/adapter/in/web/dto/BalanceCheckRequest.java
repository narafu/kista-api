package com.kista.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// 잔고 검증 설정 요청 body
public record BalanceCheckRequest(
        @Schema(description = "잔고 검증 활성화 여부 — true면 시드 등록/수정 시 가용금액 한도 검증", example = "true")
        boolean enabled) {}
