package com.kista.adapter.in.web.dto;

import com.kista.domain.model.toss.TossExchangeRate;

import java.math.BigDecimal;

public record TossExchangeRateResponse(
    BigDecimal rate,    // 매수 환율 (1 USD 기준 KRW)
    BigDecimal midRate  // 매매기준율
) {
    public static TossExchangeRateResponse from(TossExchangeRate rate) {
        return new TossExchangeRateResponse(rate.rate(), rate.midRate());
    }
}
