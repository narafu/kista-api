package com.kista.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// 미국 시장 세션 상태 응답 — UI 수동 실행 버튼 활성화 판단용
public record MarketSessionResponse(
        @Schema(allowableValues = {"DIRECT", "BLOCKED"}) String session,
        boolean isDst
) {}
