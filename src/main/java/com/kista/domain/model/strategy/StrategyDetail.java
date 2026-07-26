package com.kista.domain.model.strategy;

import java.math.BigDecimal;
import java.time.LocalDate;

// Strategy + 현재 StrategyCycle 상태 — API 응답 조립용 (TradingCycleResponse)
public record StrategyDetail(
        Strategy strategy,
        BigDecimal initialUsdDeposit,
        LocalDate startDate,    // 사이클 시작일(예정) — 미래면 아직 매매 시작 전
        Integer divisionCount,
        boolean isReverseMode,
        Double currentRound,    // INFINITE 전략만 non-null, 이력 없으면 null
        Integer currentHoldings, // 최신 cycle_position 기준 보유 수량
        VrSummary vr            // VR 전략만 non-null, 비VR은 null
) {

    // VR 전략 조회 응답 요약 — StrategyVrDetail + StrategyCycleVrDetail 합산
    public record VrSummary(
            BigDecimal value,        // 사이클 시작 시 V값 (실력 기준선)
            BigDecimal bandWidth,    // 밴드 폭 (%, 예: 15.00)
            int intervalWeeks,       // 리밸런싱 주기 (주 단위)
            int recurringAmount,     // 주기당 추가 예수금 (USD, 음수=인출)
            BigDecimal poolLimit,    // 사이클 pool 상한 금액 (USD, startAmount×poolLimitRate 파생값)
            BigDecimal poolLimitRate, // 사이클에 고정된 pool 상한 비율(0~1) — 현재 사이클 고정 스냅샷
            int gradient,            // 실력공식 경사 계수 (G) — 현재 사이클 고정 스냅샷
            // 램프 설정값 — StrategyVrDetail 원본 그대로 노출 (gradientAt/poolLimitRateAt 재계산에 필요)
            int initialGradient,             // 램프 시작 시점(경과 0주)의 gradient(G) 값
            int gGraceWeeks,                 // gradient 램프 시작 전 유예 주수
            int gStepWeeks,                  // gradient가 한 단계 상승하는 주기 (주 단위)
            int gMax,                        // gradient 램프의 상한값
            BigDecimal initialPoolLimitRate, // 램프 시작 시점(경과 0주)의 poolLimitRate 값
            int pGraceWeeks,                 // poolLimitRate 램프 시작 전 유예 주수
            int pStepWeeks,                  // poolLimitRate가 한 단계 하강하는 주기 (주 단위)
            BigDecimal poolLimitFloor        // poolLimitRate 램프의 하한값
    ) {}
}
