package com.kista.tradingcore.broker.domain.model;

import com.kista.shared.kernel.Currency;

import java.math.BigDecimal;

public record MarginItem(
        Currency currency,                     // 통화코드
        BigDecimal purchasableAmount,          // 실제 매수 가능 USD = max(integrated, foreign)
        BigDecimal usdToKrwRate                // 기준환율 (bass_exrt)
) {}
