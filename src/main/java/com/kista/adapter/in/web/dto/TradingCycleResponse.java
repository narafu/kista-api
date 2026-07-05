package com.kista.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
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
        @Schema(description = "분할 수 (INFINITE 전략만 non-null)", example = "20")
        Integer divisionCount,
        @Schema(description = "리버스모드 활성 여부 (소진 후 모드)", example = "false")
        boolean isReverseMode,
        @Schema(description = "현재 회차 (INFINITE 전략만, 이력 없으면 null)", example = "3.5")
        Double currentRound,
        @Schema(description = "최신 포지션 보유 수량", example = "0")
        Integer currentHoldings,
        @Schema(description = "VR 전략 상세 (VR 전략만 non-null)")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        VrSummary vr
) {
    // VR 전략 응답 요약 DTO
    public record VrSummary(
            @Schema(description = "V값 (실력 기준선)")
            BigDecimal value,
            @Schema(description = "밴드 폭 (%)", example = "15.00")
            BigDecimal bandWidth,
            @Schema(description = "리밸런싱 주기 (주 단위)", example = "4")
            int intervalWeeks,
            @Schema(description = "주기당 추가 예수금 (USD, 음수=인출)", example = "0")
            int recurringAmount,
            @Schema(description = "pool 상한 금액 (USD)")
            BigDecimal poolLimit,
            @Schema(description = "실력공식 경사 계수 (G)", example = "10")
            int gradient
    ) {
        // StrategyDetail.VrSummary → 응답 DTO 변환
        static VrSummary from(StrategyDetail.VrSummary s) {
            return new VrSummary(s.value(), s.bandWidth(), s.intervalWeeks(),
                    s.recurringAmount(), s.poolLimit(), s.gradient());
        }
    }

    public static TradingCycleResponse from(StrategyDetail detail) {
        Strategy c = detail.strategy();
        return new TradingCycleResponse(
                c.id(), c.accountId(),
                c.type().name(), c.status().name(),
                c.ticker().name(), detail.initialUsdDeposit(),
                c.cycleSeedType() != null ? c.cycleSeedType().name() : Strategy.CycleSeedType.NONE.name(),
                detail.divisionCount(),
                detail.isReverseMode(),
                detail.currentRound(),
                detail.currentHoldings(),
                detail.vr() != null ? VrSummary.from(detail.vr()) : null
        );
    }
}
