package com.kista.domain.port.in;

import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;

import java.math.BigDecimal;
import java.util.UUID;

public interface RegisterTradingCycleUseCase {
    TradingCycle register(UUID userId, UUID accountId, Command command);

    record Command(
            TradingCycle.Type type,
            Ticker ticker,              // null이면 전략 기본값
            BigDecimal initialUsdDeposit // null 허용 (선택 입력)
    ) {}
}
