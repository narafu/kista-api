package com.kista.tradingcore.trading.domain.model;


import java.math.BigDecimal;
import java.time.LocalDate;

// Strategy + 현재 StrategyCycle 상태 — API 응답 조립용 (TradingCycleResponse)
public record StrategyDetail(
        Strategy strategy,
        BigDecimal initialDeposit,
        LocalDate startDate,    // 사이클 시작일(예정) — 미래면 아직 매매 시작 전
        Integer divisionCount,
        boolean isReverseMode,
        Double currentRound,    // INFINITE 전략만 non-null, 이력 없으면 null
        Integer currentHoldings, // 최신 cycle_position 기준 보유 수량
        VrSummary vr,           // VR 전략만 non-null, 비VR은 null (com.kista.tradingcore.trading.domain.model.VrSummary)
        DcaSummary dca,         // DCA 전략만 non-null
        boolean cycleInProgress, // 최신 사이클이 존재하고 종료(endDate)되지 않음 — 투자금 추가 NO_ACTIVE_CYCLE 판정과 동일 기준
        BigDecimal ledgerDeposit // 최신 cycle_position 장부 예수금 (계좌 통화 USD, 포지션 없으면 null) — 투자금 인출 최대 기준
) {
}
