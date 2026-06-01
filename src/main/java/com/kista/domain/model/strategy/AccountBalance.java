package com.kista.domain.model.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record AccountBalance(
        int holdings,         // 보유 수량
        BigDecimal avgPrice,  // 평균 매입가 (holdings==0이면 null)
        BigDecimal usdDeposit // 통합주문가능금액 (USD, 환전 여부 무관 — TTTC2101R itgr_ord_psbl_amt)
) {
    /** avgPrice may be null when holdings == 0 */
    public boolean shouldSkip() {
        // 수량도 없고 예수금도 10달러 이하면 거래할 자산이 없음
        return holdings == 0 && usdDeposit.compareTo(BigDecimal.TEN) <= 0;
    }

    // 현재가를 알 때 — 0회차에서 단위금액(usdDeposit/20)이 현재가 미만이면 매수 수량이 0이 됨
    public boolean shouldSkip(BigDecimal currentPrice) {
        if (shouldSkip()) return true;
        if (holdings == 0 && currentPrice != null) {
            BigDecimal unitAmount = usdDeposit.divide(BigDecimal.valueOf(20), 2, RoundingMode.HALF_UP);
            return unitAmount.compareTo(currentPrice) < 0;
        }
        return false;
    }
}
