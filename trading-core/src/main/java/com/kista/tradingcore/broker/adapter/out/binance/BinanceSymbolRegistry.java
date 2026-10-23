package com.kista.tradingcore.broker.adapter.out.binance;

import com.kista.shared.kernel.StrategyTicker;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

// StrategyTicker ↔ 바이낸스 심볼(BTCUSDT) — 어댑터 디테일. 새 코인 추가 시 양쪽 갱신
@Component
class BinanceSymbolRegistry {

    static final String QUOTE_ASSET = "USDT"; // 견적 자산: 테더

    private static final Map<StrategyTicker, String> SYMBOLS = Map.of(
            StrategyTicker.BTC, "BTCUSDT", // 심볼: BTC/USDT 현물
            StrategyTicker.ETH, "ETHUSDT"  // 심볼: ETH/USDT 현물
    );

    String symbolOf(StrategyTicker ticker) {
        String symbol = SYMBOLS.get(ticker);
        if (symbol == null) throw new IllegalArgumentException("바이낸스 심볼 매핑 누락: " + ticker);
        return symbol;
    }

    String baseAssetOf(StrategyTicker ticker) {
        return ticker.name();
    }

    // 잔고 asset(BTC) → 지원 종목, 미지원 자산은 empty(잔고에서 제외)
    Optional<StrategyTicker> tickerOfAsset(String asset) {
        return SYMBOLS.keySet().stream().filter(t -> t.name().equals(asset)).findFirst();
    }
}
