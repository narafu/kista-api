package com.kista.tradingcore.broker.adapter.out.upbit;

import com.kista.shared.kernel.StrategyTicker;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

// StrategyTicker ↔ 업비트 마켓 코드(KRW-BTC) — 어댑터 디테일(KisExchangeRegistry와 같은 방식). 새 코인 추가 시 양쪽 갱신
@Component
class UpbitMarketRegistry {

    private static final Map<StrategyTicker, String> MARKETS = Map.of(
            StrategyTicker.BTC, "KRW-BTC",
            StrategyTicker.ETH, "KRW-ETH"
    );

    static final String USDT_MARKET = "KRW-USDT"; // 테더 원화 마켓 — 코인 DCA 종목이 아니라 김프 지표 전용

    String marketOf(StrategyTicker ticker) {
        String market = MARKETS.get(ticker);
        if (market == null) throw new IllegalArgumentException("업비트 마켓 매핑 누락: " + ticker);
        return market;
    }

    // /v1/accounts currency(BTC) → 지원 종목, 미지원 코인은 empty(잔고에서 제외)
    Optional<StrategyTicker> tickerOfCurrency(String currency) {
        return MARKETS.keySet().stream().filter(t -> t.name().equals(currency)).findFirst();
    }

    // /v1/ticker market(KRW-BTC) → 지원 종목
    Optional<StrategyTicker> tickerOfMarket(String market) {
        return MARKETS.entrySet().stream().filter(e -> e.getValue().equals(market)).map(Map.Entry::getKey).findFirst();
    }
}
