package com.kista.broker.adapter.out.internal;

import com.kista.sharedkernel.StrategyTicker;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

// KIS/Toss/Mock 3개 어댑터가 공유하는 getClosingPrices 루프 — 티커 목록을 단건 조회로 순회해 Map으로 모은다.
// BrokerPricePort default 메서드로 두지 않는 이유: Mockito가 단건 getClosingPrice만 stub한 테스트에서
// default 루프 메서드가 mock에 의해 override/무시되어 연결이 끊긴다(interface default 메서드 공통 함정).
public final class ClosingPriceLoop {

    private ClosingPriceLoop() {
    }

    // singleLookup: 각 브로커의 기존 단건 getClosingPrice 구현을 그대로 넘긴다(호출자가 tradeDate/account를 클로저로 캡처)
    public static Map<StrategyTicker, BigDecimal> collect(List<StrategyTicker> tickers,
                                                            Function<StrategyTicker, BigDecimal> singleLookup) {
        Map<StrategyTicker, BigDecimal> result = new LinkedHashMap<>();
        for (StrategyTicker ticker : tickers) {
            result.put(ticker, singleLookup.apply(ticker));
        }
        return result;
    }
}
