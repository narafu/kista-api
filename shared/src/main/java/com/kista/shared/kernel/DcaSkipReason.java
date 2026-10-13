package com.kista.shared.kernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// DCA 회차 미주문 사유 — dca_execution.skip_reason 상수명과 직결(변경 금지)
@Getter
@RequiredArgsConstructor
public enum DcaSkipReason {
    DATA_UNAVAILABLE("데이터 확보 실패"),
    CONDITION_NOT_MET("조건 미충족"),
    NO_HOLDINGS("보유 수량 없음"),
    INSUFFICIENT_BALANCE("예수금 부족"),
    BELOW_ONE_SHARE("1주 미만 금액");

    private final String label; // 한국어 표시 이름
}
