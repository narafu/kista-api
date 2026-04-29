package com.kista.domain.model;

import java.math.BigDecimal;

public record AccountBalance(
        int soxlQty,              // SOXL 보유 수량
        BigDecimal avgPrice,      // 평균 매입가 (soxlQty==0이면 null)
        BigDecimal effectiveAmt,  // 유가증권 평가액 (USD)
        BigDecimal usdDeposit     // USD 예수금
) {
    /** avgPrice may be null when soxlQty == 0 */
    public boolean shouldSkip() {
        // 수량도 없고 예수금도 10달러 이하면 거래할 자산이 없음
        return soxlQty == 0 && effectiveAmt.compareTo(BigDecimal.TEN) <= 0;
    }
}
