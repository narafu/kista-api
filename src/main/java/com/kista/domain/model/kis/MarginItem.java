package com.kista.domain.model.kis;

import java.math.BigDecimal;

public record MarginItem(
        Currency currency,                     // 통화코드
        BigDecimal integratedOrderableAmount,  // 통합주문가능금액 (itgr_ord_psbl_amt)
        BigDecimal usdToKrwRate                // 기준환율 (bass_exrt)
) {}
