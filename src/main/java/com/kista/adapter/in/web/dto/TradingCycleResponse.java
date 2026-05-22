package com.kista.adapter.in.web.dto;

import com.kista.domain.model.tradingcycle.TradingCycle;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

public record TradingCycleResponse(
        @Schema(description = "거래 사이클 고유 ID")
        UUID id,
        @Schema(description = "소속 계좌 ID")
        UUID accountId,
        @Schema(description = "전략 종류", example = "INFINITE")
        String type,
        @Schema(description = "사이클 상태", example = "ACTIVE")
        String status,
        @Schema(description = "거래 종목", example = "TQQQ")
        String ticker,
        @Schema(description = "배수", example = "1.0")
        BigDecimal multiple,
        @Schema(description = "초기 입금액 (메타 기록용)", example = "1000.00")
        BigDecimal initialUsdDeposit
) {
    public static TradingCycleResponse from(TradingCycle c) {
        return new TradingCycleResponse(
                c.id(), c.accountId(),
                c.type().name(), c.status().name(),
                c.ticker().name(), c.multiple(), c.initialUsdDeposit()
        );
    }
}
