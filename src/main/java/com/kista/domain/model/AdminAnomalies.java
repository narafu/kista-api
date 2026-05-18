package com.kista.domain.model;

import java.util.List;

// 이상 징후 집계 도메인 모델 — 컨트롤러에서 DTO 변환
public record AdminAnomalies(
    List<TradeHistory> failedTrades,      // 최근 30일 FAILED 거래
    List<Account> pausedAccounts,         // 전략 PAUSED 계좌
    List<Account> inactiveAccounts        // 최근 7일 거래 없는 ACTIVE 전략 계좌
) {}
