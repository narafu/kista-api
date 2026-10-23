package com.kista.tradingcore.stats.application.port.output;

import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.stats.domain.model.DatedValue;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

// 업비트 공개(무인증) 시세 — 김프·USDT 프리미엄 전용 (BinanceMarketDataPort·CoinbaseMarketDataPort와 같은 벤더 공개 시세 포트)
public interface UpbitMarketDataPort {

    // KRW-USDT 현재가 — 응답이 비면 IllegalStateException
    BigDecimal getUsdtKrwPrice();

    // KRW-USDT 완성 일봉 종가 최근 count개(1~400), 오래된 순 — 완성 봉만, 날짜는 KST 09:00 시작 봉의 KST 시작일
    List<DatedValue> getCompletedUsdtKrwDailyCloses(int count);

    // 코인 KRW 현재가 (KRW-<ticker>)
    Map<StrategyTicker, BigDecimal> getKrwPrices(Collection<StrategyTicker> tickers);

    // 코인 KRW 완성 일봉 종가 — getCompletedUsdtKrwDailyCloses와 같은 계약
    List<DatedValue> getCompletedKrwDailyCloses(StrategyTicker ticker, int count);
}
