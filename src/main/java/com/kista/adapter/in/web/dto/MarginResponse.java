package com.kista.adapter.in.web.dto;

import com.kista.domain.model.kis.Currency;
import com.kista.domain.model.kis.MarginItem;

import java.math.BigDecimal;

public record MarginResponse(
        Currency currency,
        BigDecimal integratedOrderableAmount,
        BigDecimal foreignOrderableAmount,
        BigDecimal purchasableAmount,
        BigDecimal usdToKrwRate
) {
    public static MarginResponse from(MarginItem item) {
        return new MarginResponse(
                item.currency(),
                item.integratedOrderableAmount(),
                item.foreignOrderableAmount(),
                item.purchasableAmount(),
                item.usdToKrwRate()
        );
    }
}
