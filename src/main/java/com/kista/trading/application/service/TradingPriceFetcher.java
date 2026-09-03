package com.kista.trading.application.service;

import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.trading.domain.model.PriceSnapshot;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.trading.application.event.TradingErrorEvent;
import com.kista.broker.application.port.output.BrokerPricePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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
    private final ApplicationEventPublisher eventPublisher; // 일괄+단건 fallback 모두 실패한 종목을 관리자에게 이벤트로 통지

    // 현재가만 필요한 경우 (종가 조회 등)
    Map<Ticker, BigDecimal> fetchPrices(List<Ticker> tickers, Account account) {
        return fetchWithFallback(tickers, account, "현재가",
                (t, acc) -> registry.require(acc, BrokerPricePort.class).getPrices(t, acc),
                (t, acc) -> registry.require(acc, BrokerPricePort.class).getPrice(t, acc));
    }

    // 현재가 + 전일종가 함께 필요한 경우 (0회차 진입 방향 판단)
    Map<Ticker, PriceSnapshot> fetchPriceSnapshots(List<Ticker> tickers, Account account) {
        Map<Ticker, com.kista.broker.domain.model.PriceSnapshot> brokerSnapshots = fetchWithFallback(tickers, account, "스냅샷",
                (t, acc) -> registry.require(acc, BrokerPricePort.class).getPriceSnapshots(t, acc),
                (t, acc) -> registry.require(acc, BrokerPricePort.class).getPriceSnapshot(t, acc));
        // broker 소유 PriceSnapshot(2필드 복제 타입) → trading 소유 PriceSnapshot 매핑 — 필드 구성 동일, 타입만 다름
        // snap==null(일괄+단건 fallback 모두 실패)이면 그대로 배제 — 호출부(collectCycleCandidate 등)가 맵에 키 부재를 이미 null-tolerant하게 처리함
        Map<Ticker, PriceSnapshot> result = new HashMap<>();
        brokerSnapshots.forEach((ticker, snap) -> {
            if (snap != null) result.put(ticker, new PriceSnapshot(snap.current(), snap.prevClose()));
        });
        return result;
    }

    // 전일종가만 필요한 경우 (매매 미리보기 배치 등) — 종목 수만큼 순차 단건 조회 대신 1회 일괄 조회
    Map<Ticker, BigDecimal> fetchPrevCloses(List<Ticker> tickers, Account account) {
        return fetchWithFallback(tickers, account, "전일종가",
                (t, acc) -> registry.require(acc, BrokerPricePort.class).getPrevCloses(t, acc),
                (t, acc) -> registry.require(acc, BrokerPricePort.class).getPrevClose(t, acc));
    }

    // 정규장 확정 종가만 필요한 경우 (마감 리포트 전용)
    Map<Ticker, BigDecimal> fetchClosingPrices(List<Ticker> tickers, LocalDate tradeDate, Account account) {
        return fetchWithFallback(tickers, account, "확정종가",
                (t, acc) -> registry.require(acc, BrokerPricePort.class).getClosingPrices(t, tradeDate, acc),
                (t, acc) -> registry.require(acc, BrokerPricePort.class).getClosingPrice(t, tradeDate, acc));
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
        // 일괄+단건 fallback 모두 실패한 종목을 모아 알림 1건으로 통지 (실패 종목 수만큼 알림이 반복 발송되는 것 방지)
        List<String> failedTickers = new ArrayList<>();
        Exception lastFailure = null;
        for (Ticker ticker : tickers) {
            // containsKey가 아닌 값 null 체크 — bulkFetch가 특정 ticker에 null 값을 담아 반환해도 단건 fallback으로 재조회
            if (result.get(ticker) == null) {
                try {
                    result.put(ticker, singleFetch.apply(ticker, account));
                } catch (Exception e) {
                    log.warn("[{}] 단건 {} 조회 실패: {}", ticker.name(), label, e.getMessage());
                    failedTickers.add(ticker.name());
                    lastFailure = e;
                }
            }
        }
        if (!failedTickers.isEmpty()) {
            eventPublisher.publishEvent(new TradingErrorEvent(null, new IllegalStateException(
                    failedTickers + " " + label + " 조회 실패(일괄+단건 모두 실패)", lastFailure)));
        }
        return result;
    }
}
