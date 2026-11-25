package com.kista.shared.kernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// VR 전략 정기 입출금 방향 — 전략 생성 정책 어휘(admin RuntimeSettings + trading StrategyCreationResolvers 공용)
@Getter
@RequiredArgsConstructor
public enum RecurringMode {
    DEPOSIT("정기 적립"), // 정기 적립
    HOLD("입출금 없음"), // 정기 입출금 없음
    WITHDRAW("정기 인출"); // 정기 인출

    private final String label; // 한국어 표시 이름 (메타 번들)
}
