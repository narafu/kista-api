package com.kista.tradingcore.trading.domain.model;

import java.math.BigDecimal;

// 진행 중 사이클 투자금 추가 인바운드 파라미터 — 모두 nullable(예수금·주식 중 최소 하나 필수)
public record InjectCapitalCommand(
        BigDecimal depositAmount, // 추가할 예수금 (USD)
        Integer shares,           // 추가할 주식 수 (sharePrice와 함께 지정)
        BigDecimal sharePrice     // 추가할 주식의 매수단가 (shares와 함께 지정)
) {
    // 입력 형태 검증 — 최소 하나 필수, 주식은 수량·단가 쌍, 지정한 값은 양수
    public void validate() {
        if (depositAmount == null && shares == null) {
            throw new IllegalArgumentException("추가할 예수금 또는 주식을 입력해야 합니다.");
        }
        if ((shares == null) != (sharePrice == null)) {
            throw new IllegalArgumentException("주식을 추가하려면 수량과 매수단가를 함께 입력해야 합니다.");
        }
        if ((depositAmount != null && depositAmount.signum() <= 0) || (shares != null && shares <= 0)
                || (sharePrice != null && sharePrice.signum() <= 0)) {
            throw new IllegalArgumentException("추가 금액과 수량은 0보다 커야 합니다.");
        }
    }

    // 추가 예수금 — 미지정이면 0
    public BigDecimal depositOrZero() {
        return depositAmount != null ? depositAmount : BigDecimal.ZERO;
    }

    // 추가 주식 수 — 미지정이면 0
    public int sharesOrZero() {
        return shares != null ? shares : 0;
    }
}
