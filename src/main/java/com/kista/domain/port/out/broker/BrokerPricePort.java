package com.kista.domain.port.out.broker;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.PriceSnapshot;
import com.kista.domain.model.strategy.Strategy.Ticker;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

// 현재가·스냅샷 조회 — KIS: 계좌 토큰 사용 / Toss: 공통 API(account 파라미터 무시)
public interface BrokerPricePort {
    BigDecimal getPrice(Ticker ticker, Account account);
    Map<Ticker, BigDecimal> getPrices(List<Ticker> tickers, Account account);
    PriceSnapshot getPriceSnapshot(Ticker ticker, Account account);
    Map<Ticker, PriceSnapshot> getPriceSnapshots(List<Ticker> tickers, Account account);
    // 전일종가만 필요한 경우 전용 — Toss는 현재가 API 호출 없이 캔들 API만 호출 (KIS는 현재가와 응답이 묶여 있어 절감 없음)
    BigDecimal getPrevClose(Ticker ticker, Account account);
    Map<Ticker, BigDecimal> getPrevCloses(List<Ticker> tickers, Account account);
}
