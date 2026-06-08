package com.kista.domain.port.in;

import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// KIS HHDFS76410000 — 복수 종목 현재가 조회 (최대 10종목)
public interface GetMultiPriceUseCase {
    Map<Ticker, BigDecimal> getPrices(UUID accountId, UUID requesterId, List<Ticker> tickers);
}
