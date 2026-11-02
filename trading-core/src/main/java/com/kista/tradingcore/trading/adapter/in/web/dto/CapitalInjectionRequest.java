package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.tradingcore.trading.domain.model.InjectCapitalCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

// 진행 중 사이클 투자금 추가 요청 — 예수금·주식 중 최소 하나 필수(서비스에서 검증), 주식은 수량·단가 동시 지정
public record CapitalInjectionRequest(
        @Schema(description = "추가할 예수금 (USD, null=추가 없음)", example = "500.00")
        @Positive @Digits(integer = 13, fraction = 2) BigDecimal depositAmount,
        @Schema(description = "추가할 주식 수 (null=추가 없음, sharePrice와 함께 지정)", example = "10")
        @Positive Integer shares,
        @Schema(description = "추가할 주식의 매수단가 (shares와 함께 지정)", example = "45.50")
        @Positive BigDecimal sharePrice
) {
    public InjectCapitalCommand toCommand() {
        return new InjectCapitalCommand(depositAmount, shares, sharePrice);
    }
}
