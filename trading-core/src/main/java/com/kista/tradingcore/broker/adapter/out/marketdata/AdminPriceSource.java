package com.kista.tradingcore.broker.adapter.out.marketdata;

import com.kista.shared.kernel.StrategyTicker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

// 관리자 키 시세 소스 한 단계(Toss 관리자, KIS 관리자) — AdminMarketPriceAdapter가 @Order 순으로 누락분만 넘기며 호출한다.
// 계약: 부분 결과만 반환(없는 종목은 빠짐), 다른 시세로 대체 금지(전일종가 실패를 현재가로 메우지 않음). 예외는 던져도 되며 어댑터가 잡는다
public interface AdminPriceSource {
    // 로그용 이름
    String name();

    // 자격증명이 없으면 false — 어댑터가 건너뜀
    boolean enabled();

    Map<StrategyTicker, BigDecimal> getPrices(List<StrategyTicker> tickers);

    Map<StrategyTicker, BigDecimal> getPrevCloses(List<StrategyTicker> tickers);

    Map<StrategyTicker, BigDecimal> getClosingPrices(List<StrategyTicker> tickers, LocalDate tradeDate);
}
