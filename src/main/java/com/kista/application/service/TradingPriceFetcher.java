package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.port.out.KisPricePort;
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

    Map<Ticker, BigDecimal> fetchPrices(List<Ticker> tickers, Account account) {
        Map<Ticker, BigDecimal> prices;
        try {
            prices = new HashMap<>(kisPricePort.getPrices(tickers, account));
        } catch (Exception e) {
            log.warn("복수종목 현재가 일괄 조회 실패, 단건 fallback 사용: {}", e.getMessage());
            prices = new HashMap<>();
        }
        // 배치 응답 누락 ticker → 단건으로 보완
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
}
