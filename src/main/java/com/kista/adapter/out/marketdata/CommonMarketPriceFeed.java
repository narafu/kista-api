package com.kista.adapter.out.marketdata;

import com.kista.broker.domain.model.PriceSnapshot;
import com.kista.domain.model.strategy.Strategy.Ticker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

// 계좌 자격증명이 필요 없는 공통 시세 피드 — Toss 관리자 공통 토큰 기반, 모의계좌(MockBrokerAdapter)가 재사용
public interface CommonMarketPriceFeed {
    BigDecimal getPrice(Ticker ticker);
    Map<Ticker, BigDecimal> getPrices(List<Ticker> tickers);
    PriceSnapshot getPriceSnapshot(Ticker ticker);
    Map<Ticker, PriceSnapshot> getPriceSnapshots(List<Ticker> tickers);
    BigDecimal getPrevClose(Ticker ticker);
    Map<Ticker, BigDecimal> getPrevCloses(List<Ticker> tickers);
    // 특정 거래일의 확정 종가(일봉) — 조회 실패/봉 없으면 현재가로 폴백
    BigDecimal getClosingPrice(Ticker ticker, LocalDate tradeDate);
}
