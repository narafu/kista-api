package com.kista.domain.model.tradingcycle;

import java.math.BigDecimal;

public record RegisterCycleCommand(
        TradingCycle.Type type,
        TradingCycle.Ticker ticker,                          // null이면 전략 기본값
        BigDecimal initialUsdDeposit,                        // null 허용 (선택 입력)
        TradingCycle.CycleSeedType cycleSeedType             // null이면 NONE으로 처리
) {}
