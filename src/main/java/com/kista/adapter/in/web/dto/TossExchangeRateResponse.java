package com.kista.adapter.in.web.dto;

import com.kista.domain.model.toss.TossExchangeRate;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

public record TossExchangeRateResponse(
    @Schema(description = "매수 환율 (1 USD 기준 KRW)")
    BigDecimal rate,    // 매수 환율 (1 USD 기준 KRW)
    @Schema(description = "매매기준율")
    BigDecimal midRate  // 매매기준율
) {
    public static TossExchangeRateResponse from(TossExchangeRate rate) {
        return new TossExchangeRateResponse(rate.rate(), rate.midRate());
    }
}
