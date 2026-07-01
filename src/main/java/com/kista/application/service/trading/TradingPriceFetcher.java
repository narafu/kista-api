package com.kista.application.service.trading;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.PriceSnapshot;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.broker.BrokerPricePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

// 복수종목 현재가 일괄 조회 + 누락분 단건 fallback
@Component
@RequiredArgsConstructor
@Slf4j
class TradingPriceFetcher {

    private final BrokerAdapterRegistry registry;

    // 현재가만 필요한 경우 (종가 조회 등)
    Map<Ticker, BigDecimal> fetchPrices(List<Ticker> tickers, Account account) {
        return fetchWithFallback(tickers, account, "현재가",
                (t, acc) -> registry.require(acc, BrokerPricePort.class).getPrices(t, acc),
                (t, acc) -> registry.require(acc, BrokerPricePort.class).getPrice(t, acc));
    }

    // 현재가 + 전일종가 함께 필요한 경우 (0회차 진입 방향 판단)
    Map<Ticker, PriceSnapshot> fetchPriceSnapshots(List<Ticker> tickers, Account account) {
        return fetchWithFallback(tickers, account, "스냅샷",
                (t, acc) -> registry.require(acc, BrokerPricePort.class).getPriceSnapshots(t, acc),
                (t, acc) -> registry.require(acc, BrokerPricePort.class).getPriceSnapshot(t, acc));
    }

    // 복수종목 일괄 조회 실패(또는 일부 누락) 시 종목별 단건 fallback — 두 메서드 공용 골격
    private <T> Map<Ticker, T> fetchWithFallback(List<Ticker> tickers, Account account, String label,
                                                  BiFunction<List<Ticker>, Account, Map<Ticker, T>> bulkFetch,
                                                  BiFunction<Ticker, Account, T> singleFetch) {
        Map<Ticker, T> result;
        try {
            result = new HashMap<>(bulkFetch.apply(tickers, account));
        } catch (Exception e) {
            log.warn("복수종목 {} 일괄 조회 실패, 단건 fallback 사용: {}", label, e.getMessage());
            result = new HashMap<>();
        }
        for (Ticker ticker : tickers) {
            if (!result.containsKey(ticker)) {
                try {
                    result.put(ticker, singleFetch.apply(ticker, account));
                } catch (Exception e) {
                    log.warn("[{}] 단건 {} 조회 실패: {}", ticker.name(), label, e.getMessage());
                }
            }
        }
        return result;
    }
}
