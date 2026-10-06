package com.kista.trading.adapter.in.web.dto;

import com.kista.trading.domain.model.DstInfo;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.kista.trading.domain.model.Strategy;
import com.kista.trading.domain.model.StrategyDetail;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import com.kista.sharedkernel.StrategyCycleSeedType;

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
        @Schema(description = "사이클 시작일 (미래면 시작예정일 — 시작예정일 밤 미국장부터 매매 시작)", example = "2026-08-01")
        LocalDate startDate,
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
        VrSummary vr,
        @Schema(description = "등록 응답 전용 — 오늘 시작인데 오늘 개장 배치(22:30 KST, 월~금) 이후 등록돼 오늘 밤 장 시작 주문(INFINITE 매도·VR 사다리)이 자동 생성되지 않으면 true(PRIVACY는 항상 false, 미국 휴장일엔 거짓 양성 가능). 등록 외 응답에선 생략")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean todayOpenBatchMissed
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
            @Schema(description = "현재 pool(예수금, USD) — 최신 포지션 기준, 개장값(initialUsdDeposit)과 다름")
            BigDecimal currentPool,
            @Schema(description = "pool 상한 비율(0~1) — 현재 사이클 고정 스냅샷", example = "0.75")
            BigDecimal poolLimitRate,
            @Schema(description = "실력공식 경사 계수 (G) — 현재 사이클 고정 스냅샷", example = "10")
            int gradient,
            @Schema(description = "램프 시작 시점(경과 0주) gradient(G) 값", example = "10")
            int initialGradient,
            @Schema(description = "gradient 램프 시작 전 유예 주수", example = "52")
            int gGraceWeeks,
            @Schema(description = "gradient가 한 단계 상승하는 주기 (주 단위)", example = "26")
            int gStepWeeks,
            @Schema(description = "gradient 램프 상한값", example = "20")
            int gMax,
            @Schema(description = "램프 시작 시점(경과 0주) poolLimitRate 값", example = "0.75")
            BigDecimal initialPoolLimitRate,
            @Schema(description = "poolLimitRate 램프 시작 전 유예 주수", example = "52")
            int pGraceWeeks,
            @Schema(description = "poolLimitRate가 한 단계 하강하는 주기 (주 단위)", example = "26")
            int pStepWeeks,
            @Schema(description = "poolLimitRate 램프 하한값", example = "0.50")
            BigDecimal poolLimitFloor
    ) {
        // trading 소유 VrSummary(com.kista.trading.domain.model.VrSummary) → 응답 DTO 변환
        static VrSummary from(com.kista.trading.domain.model.VrSummary s) {
            return new VrSummary(s.value(), s.bandWidth(), s.intervalWeeks(),
                    s.recurringAmount(), s.poolLimit(), s.currentPool(), s.poolLimitRate(), s.gradient(),
                    s.initialGradient(), s.gGraceWeeks(), s.gStepWeeks(), s.gMax(),
                    s.initialPoolLimitRate(), s.pGraceWeeks(), s.pStepWeeks(), s.poolLimitFloor());
        }
    }

    public static TradingCycleResponse from(StrategyDetail detail) {
        Strategy c = detail.strategy();
        return new TradingCycleResponse(
                c.id(), c.accountId(),
                c.type().name(), c.status().name(),
                c.ticker().name(), detail.initialUsdDeposit(),
                detail.startDate(),
                c.cycleSeedType() != null ? c.cycleSeedType().name() : StrategyCycleSeedType.NONE.name(),
                detail.divisionCount(),
                detail.isReverseMode(),
                detail.currentRound(),
                detail.currentHoldings(),
                detail.vr() != null ? VrSummary.from(detail.vr()) : null,
                null
        );
    }

    // 등록 응답 — 오늘 개장 배치 이후 등록 여부를 함께 싣는다(판정 SSOT는 DstInfo). 동작은 바꾸지 않고 안내만 —
    // 그날 AT_OPEN 주문은 개장 후 수동 실행(ManualTradingService, UI "바로 주문")으로만 접수할 수 있다
    public static TradingCycleResponse forRegistration(StrategyDetail detail) {
        TradingCycleResponse r = from(detail);
        return new TradingCycleResponse(r.id, r.accountId, r.type, r.status, r.ticker, r.initialUsdDeposit, r.startDate,
                r.cycleSeedType, r.divisionCount, r.isReverseMode, r.currentRound, r.currentHoldings, r.vr,
                !detail.strategy().isPrivacy() && DstInfo.openBatchMissedFor(detail.startDate())); // PRIVACY는 AT_OPEN 주문이 없어 해당 없음
    }
}
