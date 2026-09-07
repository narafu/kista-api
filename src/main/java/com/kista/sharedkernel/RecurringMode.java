package com.kista.sharedkernel;

// VR 전략 정기 입출금 방향 — 전략 생성 정책 어휘(admin RuntimeSettings + trading StrategyCreationResolvers 공용)
public enum RecurringMode {
    DEPOSIT, // 정기 적립
    HOLD, // 정기 입출금 없음
    WITHDRAW // 정기 인출
}
