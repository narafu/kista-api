package com.kista.shared.kernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// DCA 규칙 규모 유형 — 상수명 DB·API 직결(변경 금지)
@Getter
@RequiredArgsConstructor
public enum DcaSizingType {
    AMOUNT("고정 금액"),
    HOLDING_PERCENT("보유 비율"); // SELL 전용

    private final String label; // 한국어 표시 이름
}
