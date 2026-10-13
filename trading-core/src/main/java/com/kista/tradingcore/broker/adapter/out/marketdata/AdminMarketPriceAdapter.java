package com.kista.tradingcore.broker.adapter.out.marketdata;

import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.application.port.output.AdminMarketPricePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

// 관리자 시세 체인 — 소스를 @Order 순(Toss → KIS)으로 돌며 누락 종목만 다음 소스에 넘긴다. 예외 없이 부분 결과 반환
@Slf4j
@Component
class AdminMarketPriceAdapter implements AdminMarketPricePort {

    private final List<AdminPriceSource> sources; // Spring이 @Order 순으로 정렬해 주입

    AdminMarketPriceAdapter(List<AdminPriceSource> sources) {
        this.sources = sources;
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getPrices(List<StrategyTicker> tickers) {
        return resolve("현재가", tickers, AdminPriceSource::getPrices);
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getPrevCloses(List<StrategyTicker> tickers) {
        return resolve("전일종가", tickers, AdminPriceSource::getPrevCloses);
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getClosingPrices(List<StrategyTicker> tickers, LocalDate tradeDate) {
        return resolve("확정종가", tickers, (source, missing) -> source.getClosingPrices(missing, tradeDate));
    }

    // 공통 골격 — 소스별로 누락분만 조회, 소스 예외는 warn 후 다음 단계로
    private Map<StrategyTicker, BigDecimal> resolve(String label, List<StrategyTicker> tickers,
                                                    BiFunction<AdminPriceSource, List<StrategyTicker>, Map<StrategyTicker, BigDecimal>> fetch) {
        if (tickers.isEmpty()) return Map.of();

        Map<StrategyTicker, BigDecimal> result = new LinkedHashMap<>();
        List<StrategyTicker> missing = List.copyOf(tickers);
        for (AdminPriceSource source : sources) {
            if (missing.isEmpty()) break;
            if (!source.enabled()) continue;
            try {
                Map<StrategyTicker, BigDecimal> got = fetch.apply(source, missing);
                // 요청하지 않았거나 null인 값은 무시 — 누락으로 취급
                for (StrategyTicker t : missing) {
                    BigDecimal v = got.get(t);
                    if (v != null) result.put(t, v);
                }
            } catch (Exception e) {
                log.warn("관리자 시세 {} {} 조회 실패 — 다음 단계: tickers={}, error={}", source.name(), label, missing, e.getMessage());
            }
            missing = tickers.stream().filter(t -> !result.containsKey(t)).distinct().toList();
            if (!missing.isEmpty()) {
                log.warn("관리자 시세 {} {} 누락 — 다음 단계: {}", source.name(), label, missing);
            }
        }
        return result;
    }
}
