package com.kista.domain.model.kis;

import java.math.BigDecimal;

public record MarginItem(
        String currency,                      // 통화코드 (USD, KRW)
        BigDecimal integratedOrderableAmount  // 통합주문가능금액 (itgr_ord_psbl_amt)
) {}
