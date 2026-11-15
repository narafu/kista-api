package com.kista.tradingcore.broker.adapter.out.toss;

import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.application.port.output.AdminMarketPricePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

// 관리자 시세 — Toss 관리자 토큰(공통 API). 예외 없이 부분 결과만 반환하고, 실패 종목은 대체값 없이 빠뜨려 BrokerRouter가 계좌 어댑터로 채운다
@Slf4j
@Component
@RequiredArgsConstructor
class TossAdminPriceSource implements AdminMarketPricePort {

    private final TossPriceApi tossPriceApi;

    @Override
    public Map<StrategyTicker, BigDecimal> getPrices(List<StrategyTicker> tickers) {
        return safe("현재가", tickers, () -> tossPriceApi.fetchPricesPartial(tickers));
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getPrevCloses(List<StrategyTicker> tickers) {
        // 캔들 실패 시 현재가 폴백 금지 — 성공분만
        return safe("전일종가", tickers, () -> collect(tickers, t -> tossPriceApi.fetchPrevCloseCached(t.name())));
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getClosingPrices(List<StrategyTicker> tickers, LocalDate tradeDate) {
        // 확정 캔들 실패 시 라이브가 폴백 금지 — 성공분만
        return safe("확정종가", tickers, () -> collect(tickers, t -> tossPriceApi.fetchConfirmedClose(t, tradeDate)));
    }

    // 빈 요청은 호출 없이 빈 Map, 예외는 warn 후 빈 Map, 요청하지 않았거나 null인 값은 누락으로 취급
    private static Map<StrategyTicker, BigDecimal> safe(String label, List<StrategyTicker> tickers,
                                                        Supplier<Map<StrategyTicker, BigDecimal>> fetch) {
        if (tickers.isEmpty()) return Map.of();
        Map<StrategyTicker, BigDecimal> result = new LinkedHashMap<>();
        try {
            Map<StrategyTicker, BigDecimal> got = fetch.get();
            for (StrategyTicker t : tickers) {
                BigDecimal v = got.get(t);
                if (v != null) result.put(t, v);
            }
        } catch (Exception e) {
            log.warn("관리자 시세 Toss {} 조회 실패 — 계좌 기준 조회로 대체: tickers={}, error={}", label, tickers, e.getMessage());
        }
        return result;
    }

    private static Map<StrategyTicker, BigDecimal> collect(List<StrategyTicker> tickers,
                                                           Function<StrategyTicker, Optional<BigDecimal>> fetch) {
        Map<StrategyTicker, BigDecimal> result = new LinkedHashMap<>();
        for (StrategyTicker t : tickers) {
            fetch.apply(t).ifPresent(v -> result.put(t, v));
        }
        return result;
    }
}
