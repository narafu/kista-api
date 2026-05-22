package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface KisPricePort {
    BigDecimal getPrice(Ticker ticker, Account account);
    Map<Ticker, BigDecimal> getPrices(List<Ticker> tickers, Account account);
}
