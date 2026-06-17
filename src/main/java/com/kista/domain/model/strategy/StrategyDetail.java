package com.kista.domain.model.strategy;

import java.math.BigDecimal;

// Strategy + 현재 StrategyCycle 상태 — API 응답 조립용 (TradingCycleResponse)
public record StrategyDetail(
        Strategy strategy,
        BigDecimal initialUsdDeposit,
        boolean isReverseMode,
        boolean canManualExecute,  // 수동 즉시 실행 가능 여부
        boolean supportsPreview    // 미리보기 탭 표시 여부
) {}
