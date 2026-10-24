package com.kista.shared.kernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// DCA 회차 판정 결과 상태 — dca_execution.status 상수명과 직결(변경 금지). 메타 라벨을 root가 내려주도록 trading에서 승격
@Getter
@RequiredArgsConstructor
public enum DcaExecutionStatus {
    ORDERED("주문됨"),   // 주문 생성
    SKIPPED("건너뜀");   // 미주문 (사유는 DcaSkipReason)

    private final String label; // 한국어 표시 이름 (메타 dcaExecutionStatuses)
}
