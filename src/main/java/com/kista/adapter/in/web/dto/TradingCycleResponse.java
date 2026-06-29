package com.kista.adapter.in.web.dto;

import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyDetail;
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
        @Schema(description = "초기 입금액", example = "2000.00")
        BigDecimal initialUsdDeposit,
        @Schema(description = "연속 사이클 정책", example = "NONE")
        String cycleSeedType,
        @Schema(description = "분할 수 (20/30/40)", example = "20")
        int divisionCount,
        @Schema(description = "리버스모드 활성 여부 (소진 후 모드)", example = "false")
        boolean isReverseMode,
        @Schema(description = "현재 회차 (INFINITE 전략만, 이력 없으면 null)", example = "3.5")
        Double currentRound
) {
    public static TradingCycleResponse from(StrategyDetail detail) {
        Strategy c = detail.strategy();
        return new TradingCycleResponse(
                c.id(), c.accountId(),
                c.type().name(), c.status().name(),
                c.ticker().name(), detail.initialUsdDeposit(),
                c.cycleSeedType() != null ? c.cycleSeedType().name() : Strategy.CycleSeedType.NONE.name(),
                detail.divisionCount() != null ? detail.divisionCount() : Strategy.DEFAULT_DIVISION_COUNT,
                detail.isReverseMode(),
                detail.currentRound()
        );
    }
}
