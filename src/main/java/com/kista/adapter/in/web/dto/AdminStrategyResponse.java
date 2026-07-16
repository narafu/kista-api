package com.kista.adapter.in.web.dto;

import com.kista.domain.model.strategy.Strategy;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

// 관리자 전략 목록 응답 DTO — 계좌별 전략 드롭다운 및 계좌 목록 내 strategies 배열에 사용
public record AdminStrategyResponse(
        @Schema(description = "전략 고유 ID")
        UUID id,
        @Schema(description = "전략 종류", example = "INFINITE")
        String type,
        @Schema(description = "전략 상태", example = "ACTIVE")
        String status,
        @Schema(description = "거래 종목", example = "SOXL")
        String ticker,
        @Schema(description = "사이클 종료 후 재등록 정책", example = "NONE")
        String cycleSeedType
) {
    public static AdminStrategyResponse from(Strategy strategy) {
        return new AdminStrategyResponse(
                strategy.id(),
                strategy.type().name(),
                strategy.status().name(),
                strategy.ticker().name(),
                strategy.cycleSeedType().name()
        );
    }
}
