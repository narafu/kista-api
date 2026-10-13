package com.kista.shared.kernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 매수/매도 방향 — broker/privacy/matching이 공유하는 전역 어휘(2026-09-09 own-type 원장 재판정으로 승격)
@Getter
@RequiredArgsConstructor
public enum OrderDirection {
    BUY("매수"),
    SELL("매도");

    private final String label; // 한국어 표시 이름 (메타 orderDirections)
}
