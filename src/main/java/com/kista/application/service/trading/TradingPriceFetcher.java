package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.port.out.KisPricePort;
import com.kista.domain.port.out.KisPricePort.PriceSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 복수종목 현재가 일괄 조회 + 누락분 단건 fallback
@Component
@RequiredArgsConstructor
@Slf4j
class TradingPriceFetcher {

    private final KisPricePort kisPricePort;

    // 현재가만 필요한 경우 (종가 조회 등)
    Map<Ticker, BigDecimal> fetchPrices(List<Ticker> tickers, Account account) {
        Map<Ticker, BigDecimal> prices;
        try {
            prices = new HashMap<>(kisPricePort.getPrices(tickers, account));
        } catch (Exception e) {
            log.warn("복수종목 현재가 일괄 조회 실패, 단건 fallback 사용: {}", e.getMessage());
            prices = new HashMap<>();
        }
        for (Ticker ticker : tickers) {
            if (!prices.containsKey(ticker)) {
                try {
                    prices.put(ticker, kisPricePort.getPrice(ticker, account));
                } catch (Exception e) {
                    log.warn("[{}] 단건 현재가 조회 실패: {}", ticker.name(), e.getMessage());
                }
            }
        }
        return prices;
    }

    // 현재가 + 전일종가 함께 필요한 경우 (0회차 진입 방향 판단)
    Map<Ticker, PriceSnapshot> fetchPriceSnapshots(List<Ticker> tickers, Account account) {
        Map<Ticker, PriceSnapshot> snapshots;
        try {
            snapshots = new HashMap<>(kisPricePort.getPriceSnapshots(tickers, account));
        } catch (Exception e) {
            log.warn("복수종목 스냅샷 일괄 조회 실패, 단건 fallback 사용: {}", e.getMessage());
            snapshots = new HashMap<>();
        }
        for (Ticker ticker : tickers) {
            if (!snapshots.containsKey(ticker)) {
                try {
                    snapshots.put(ticker, kisPricePort.getPriceSnapshot(ticker, account));
                } catch (Exception e) {
                    log.warn("[{}] 단건 스냅샷 조회 실패: {}", ticker.name(), e.getMessage());
                }
            }
        }
        return snapshots;
    }
}
