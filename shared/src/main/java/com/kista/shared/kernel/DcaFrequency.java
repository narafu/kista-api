package com.kista.shared.kernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// DCA 실행 주기 — strategy_dca_rule.frequency 컬럼 상수명과 직결(변경 금지)
@Getter
@RequiredArgsConstructor
public enum DcaFrequency {
    DAILY("매일"),   // 매 미국 거래일
    WEEKLY("매주"),  // 지정 요일(같은 주 안 이월)
    MONTHLY("매월"); // 지정 일자 또는 말일(같은 달 안 이월)

    private final String label; // 한국어 표시 이름
}
