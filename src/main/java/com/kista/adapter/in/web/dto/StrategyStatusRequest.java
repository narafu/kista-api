package com.kista.adapter.in.web.dto;

import com.kista.domain.model.strategy.Strategy;
import io.swagger.v3.oas.annotations.media.Schema;

// 관리자 전략 상태 변경 요청 DTO — ACTIVE(재개) / PAUSED(일시정지)
public record StrategyStatusRequest(
        @Schema(description = "변경할 전략 상태 — ACTIVE(재개) / PAUSED(일시정지)", example = "PAUSED")
        Strategy.Status status) {}
