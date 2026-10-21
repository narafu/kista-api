package com.kista.shared.kernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// DCA 규칙 부족 정책 — 가용(원화·보유)이 계획 규모보다 적을 때. 상수명 DB·API 직결(변경 금지)
@Getter
@RequiredArgsConstructor
public enum DcaShortfallPolicy {
    PARTIAL("가용만큼 주문"),
    REJECT("주문하지 않음");

    private final String label; // 한국어 표시 이름
}
