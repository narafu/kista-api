package com.kista.api.admin.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.kista.shared.kernel.StrategyStatus;

// 관리자 전략 상태 변경 요청 DTO — ACTIVE(재개) / PAUSED(일시중지)
public record StrategyStatusRequest(
        @Schema(description = "변경할 전략 상태 — ACTIVE(재개) / PAUSED(일시중지)", example = "PAUSED")
        StrategyStatus status) {}
