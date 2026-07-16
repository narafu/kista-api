package com.kista.adapter.in.web.dto;

import com.kista.domain.model.broker.Currency;
import com.kista.domain.model.broker.MarginItem;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

public record MarginResponse(
        @Schema(description = "통화코드", example = "USD")
        Currency currency,
        @Schema(description = "통합주문가능금액 (통합증거금 ON일 때만 양수)")
        BigDecimal integratedOrderableAmount,
        @Schema(description = "외화일반주문가능금액 (통합증거금 무관, 항상 유효)")
        BigDecimal foreignOrderableAmount,
        @Schema(description = "실제 매수 가능 USD (통합·외화주문가능금액 중 큰 값)")
        BigDecimal purchasableAmount,
        @Schema(description = "기준환율 (1 USD = ? KRW)")
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
