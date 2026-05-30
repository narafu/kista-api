package com.kista.domain.model.kis;

import java.math.BigDecimal;

public record MarginItem(
        Currency currency,                     // 통화코드
        BigDecimal integratedOrderableAmount,  // 통합주문가능금액 (itgr_ord_psbl_amt) — 통합증거금 ON일 때만 양수
        BigDecimal foreignOrderableAmount,     // 외화일반주문가능금액 (frcr_gnrl_ord_psbl_amt) — 통합증거금 무관, 항상 유효
        BigDecimal purchasableAmount,          // 실제 매수 가능 USD = max(integrated, foreign)
        BigDecimal usdToKrwRate                // 기준환율 (bass_exrt)
) {}
