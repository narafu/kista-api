package com.kista.adapter.in.web.dto;

import com.kista.domain.model.strategy.Strategy;

// 관리자 전략 상태 변경 요청 DTO — ACTIVE(재개) / PAUSED(일시정지)
public record StrategyStatusRequest(Strategy.Status status) {}
