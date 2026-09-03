package com.kista.domain.model.strategy;

import com.kista.trading.domain.model.VrSummary;

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
        VrSummary vr            // VR 전략만 non-null, 비VR은 null (com.kista.trading.domain.model.VrSummary)
) {}
