package com.kista.domain.port.out;

import com.kista.domain.model.strategy.PriceSnapshot;
import com.kista.domain.model.strategy.Strategy.Ticker;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

// 공통 API — 개별 계좌 토큰 불필요, 관리자 자격증명으로 조회
public interface TossPricePort {
    BigDecimal getPrice(Ticker ticker);
    Map<Ticker, BigDecimal> getPrices(List<Ticker> tickers);

    PriceSnapshot getPriceSnapshot(Ticker ticker);
    Map<Ticker, PriceSnapshot> getPriceSnapshots(List<Ticker> tickers);
}
