package com.kista.adapter.out.marketdata;

import com.kista.broker.domain.model.PriceSnapshot;
import com.kista.sharedkernel.StrategyTicker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

// 계좌 자격증명이 필요 없는 공통 시세 피드 — Toss 관리자 공통 토큰 기반, 모의계좌(MockBrokerAdapter)가 재사용
public interface CommonMarketPriceFeed {
    BigDecimal getPrice(StrategyTicker ticker);
    Map<StrategyTicker, BigDecimal> getPrices(List<StrategyTicker> tickers);
    PriceSnapshot getPriceSnapshot(StrategyTicker ticker);
    Map<StrategyTicker, PriceSnapshot> getPriceSnapshots(List<StrategyTicker> tickers);
    BigDecimal getPrevClose(StrategyTicker ticker);
    Map<StrategyTicker, BigDecimal> getPrevCloses(List<StrategyTicker> tickers);
    // 특정 거래일의 확정 종가(일봉) — 조회 실패/봉 없으면 현재가로 폴백
    BigDecimal getClosingPrice(StrategyTicker ticker, LocalDate tradeDate);
}
