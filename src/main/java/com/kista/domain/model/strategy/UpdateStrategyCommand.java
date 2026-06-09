package com.kista.domain.model.strategy;

// 전략 수정 인바운드 파라미터
public record UpdateStrategyCommand(
        Strategy.CycleSeedType cycleSeedType // null이면 기존 값 유지
) {}
