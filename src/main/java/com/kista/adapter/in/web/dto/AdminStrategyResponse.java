package com.kista.adapter.in.web.dto;

import com.kista.domain.model.strategy.Strategy;

import java.util.UUID;

// 관리자 전략 목록 응답 DTO — 계좌별 전략 드롭다운 및 계좌 목록 내 strategies 배열에 사용
public record AdminStrategyResponse(
        UUID id,
        String type,
        String status,
        String ticker,
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
