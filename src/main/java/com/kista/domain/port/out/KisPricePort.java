package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.PriceSnapshot;
import com.kista.domain.model.strategy.Strategy.Ticker;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface KisPricePort {
    // 현재가만 필요한 경우 (통계 조회 등)
    BigDecimal getPrice(Ticker ticker, Account account);
    Map<Ticker, BigDecimal> getPrices(List<Ticker> tickers, Account account);

    // 현재가 + 전일종가 함께 필요한 경우 (0회차 진입 방향 판단)
    PriceSnapshot getPriceSnapshot(Ticker ticker, Account account);
    Map<Ticker, PriceSnapshot> getPriceSnapshots(List<Ticker> tickers, Account account);
}
