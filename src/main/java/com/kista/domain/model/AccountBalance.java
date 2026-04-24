package com.kista.domain.model;

import java.math.BigDecimal;

public record AccountBalance(
        int soxlQty,
        BigDecimal avgPrice,
        BigDecimal effectiveAmt,
        BigDecimal usdDeposit
) {
    /** avgPrice may be null when soxlQty == 0 */
    public boolean shouldSkip() {
        return soxlQty == 0 && effectiveAmt.compareTo(BigDecimal.TEN) <= 0;
    }
}
