package com.kista.domain.model.tradingcycle;

public record UpdateCycleCommand(
        TradingCycle.CycleSeedType cycleSeedType // null이면 기존 값 유지
) {}
