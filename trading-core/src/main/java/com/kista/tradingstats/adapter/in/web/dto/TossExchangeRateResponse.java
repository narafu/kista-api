package com.kista.tradingstats.adapter.in.web.dto;

import com.kista.broker.domain.model.ExchangeRateQuote;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

public record TossExchangeRateResponse(
    @Schema(description = "매수 환율 (1 USD 기준 KRW)")
    BigDecimal rate,    // 매수 환율 (1 USD 기준 KRW)
    @Schema(description = "매매기준율")
    BigDecimal midRate  // 매매기준율
) {
    public static TossExchangeRateResponse from(ExchangeRateQuote rate) {
        return new TossExchangeRateResponse(rate.rate(), rate.midRate());
    }
}
