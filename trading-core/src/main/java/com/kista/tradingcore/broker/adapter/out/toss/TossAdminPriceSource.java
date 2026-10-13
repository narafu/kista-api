package com.kista.tradingcore.broker.adapter.out.toss;

import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.adapter.out.marketdata.AdminPriceSource;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

// 관리자 시세 1순위 — Toss 관리자 토큰(공통 API). 실패 종목은 대체값 없이 빠뜨려 KIS 관리자로 넘긴다
@Component
@Order(1)
@RequiredArgsConstructor
class TossAdminPriceSource implements AdminPriceSource {

    private final TossPriceApi tossPriceApi;

    @Override
    public String name() {
        return "Toss 관리자";
    }

    @Override
    public boolean enabled() {
        return true; // Toss 관리자 자격증명 누락은 호출 시 예외 → 어댑터가 다음 단계로
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getPrices(List<StrategyTicker> tickers) {
        return tossPriceApi.fetchPricesPartial(tickers);
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getPrevCloses(List<StrategyTicker> tickers) {
        // 캔들 실패 시 현재가 폴백 금지 — 성공분만
        return collect(tickers, t -> tossPriceApi.fetchPrevCloseCached(t.name()));
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getClosingPrices(List<StrategyTicker> tickers, LocalDate tradeDate) {
        // 확정 캔들 실패 시 라이브가 폴백 금지 — 성공분만
        return collect(tickers, t -> tossPriceApi.fetchConfirmedClose(t, tradeDate));
    }

    private static Map<StrategyTicker, BigDecimal> collect(List<StrategyTicker> tickers,
                                                           Function<StrategyTicker, java.util.Optional<BigDecimal>> fetch) {
        Map<StrategyTicker, BigDecimal> result = new LinkedHashMap<>();
        for (StrategyTicker t : tickers) {
            fetch.apply(t).ifPresent(v -> result.put(t, v));
        }
        return result;
    }
}
