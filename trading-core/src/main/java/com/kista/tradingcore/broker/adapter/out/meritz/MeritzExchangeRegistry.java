package com.kista.tradingcore.broker.adapter.out.meritz;

import com.kista.shared.kernel.StrategyTicker;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.NoSuchElementException;

// StrategyTicker → 메리츠 거래소 코드 매핑 (어댑터 디테일 — 도메인 격리)
// vendorId: 주문·잔고 ovrs_exch_vndr_idno, marketCode: 시세 mrkt_cls_code
@Component
class MeritzExchangeRegistry {

    private static final Map<StrategyTicker, Mapping> MAP = Map.of(
            StrategyTicker.TQQQ, new Mapping("0537", "OQ"),
            StrategyTicker.SOXL, new Mapping("0066", "AX"),
            StrategyTicker.USD, new Mapping("0066", "AX"),
            StrategyTicker.MAGX, new Mapping("0066", "AX"),
            StrategyTicker.UPRO, new Mapping("0066", "AX"),
            StrategyTicker.QLD, new Mapping("0066", "AX")
    );

    String vendorId(StrategyTicker ticker) {
        return lookup(ticker).vendorId();
    }

    String marketCode(StrategyTicker ticker) {
        return lookup(ticker).marketCode();
    }

    private Mapping lookup(StrategyTicker ticker) {
        Mapping m = MAP.get(ticker);
        if (m == null) throw new NoSuchElementException("메리츠 거래소 매핑 누락: " + ticker);
        return m;
    }

    private record Mapping(String vendorId, String marketCode) {}
}
