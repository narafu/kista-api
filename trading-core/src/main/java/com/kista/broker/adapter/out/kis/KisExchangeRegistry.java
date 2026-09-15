package com.kista.broker.adapter.out.kis;

import com.kista.sharedkernel.StrategyTicker;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.NoSuchElementException;

// StrategyTicker → KIS 거래소 코드 매핑 (어댑터 디테일 — 도메인 격리)
// OVRS_EXCG_CD: 주문/체결/잔고 API용 (4자리), EXCD: 시세 API용 (3자리)
@Component
class KisExchangeRegistry {

    private static final String DEFAULT_US_EXCHANGE = "NASD"; // 잔고/일별거래/기간손익 "미국 전체" 조회용

    private static final Map<StrategyTicker, Mapping> MAP = Map.of(
            StrategyTicker.TQQQ, new Mapping("NASD", "NAS"),
            StrategyTicker.SOXL, new Mapping("AMEX", "AMS"),
            StrategyTicker.USD,  new Mapping("AMEX", "AMS"),
            StrategyTicker.MAGX, new Mapping("AMEX", "AMS")
    );

    public String ovrsExcgCd(StrategyTicker ticker) {
        return lookup(ticker).ovrsExcgCd();
    }

    public String excd(StrategyTicker ticker) {
        return lookup(ticker).excd();
    }

    public String defaultUsExchange() {
        return DEFAULT_US_EXCHANGE;
    }

    private Mapping lookup(StrategyTicker ticker) {
        Mapping m = MAP.get(ticker);
        if (m == null) throw new NoSuchElementException("KIS 거래소 매핑 누락: " + ticker);
        return m;
    }

    private record Mapping(String ovrsExcgCd, String excd) {}
}
