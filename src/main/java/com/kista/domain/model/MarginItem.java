package com.kista.domain.model;

import java.math.BigDecimal;

public record MarginItem(
        String currency,                      // 통화코드 (USD, KRW)
        BigDecimal integratedOrderableAmount, // 통합주문가능금액 (itgr_ord_psbl_amt)
        BigDecimal foreignBalance             // 외화잔고 (frcr_dncl_amt_2)
) {}
