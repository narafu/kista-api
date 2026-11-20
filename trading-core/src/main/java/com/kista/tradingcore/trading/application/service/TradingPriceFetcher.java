package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.adapter.out.marketdata.MarketDataClient;
import com.kista.tradingcore.broker.application.port.output.BrokerPricePort;
import com.kista.tradingcore.broker.application.service.BrokerCallGuard;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.BrokerCandle;
import com.kista.tradingcore.broker.domain.model.PriceSnapshot;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.privacy.application.port.output.PrivacyTradePort;
import com.kista.tradingcore.privacy.domain.model.PrivacyTradeBase;
import com.kista.tradingcore.trading.application.event.TradingErrorEvent;
import com.kista.tradingcore.trading.application.service.support.TradingParallelRunner;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.TradingAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// 주문 경로 시세 조회 — 현재가는 각 계좌의 증권사에서, 전일종가·확정종가는 공용 시세(MarketDataClient)에서 읽는다 + 배치 시작 시점 가격 컨텍스트 조립
@Component
@RequiredArgsConstructor
@Slf4j
class TradingPriceFetcher {

    private final BrokerPricePort brokerPricePort; // 계좌 증권사 현재가(currentPrices) — 관리자 시세를 거치지 않는다
    private final MarketDataClient marketData; // 전일종가·확정종가 공용 시세
    private final MarketCalendarPort marketCalendarPort; // KST 거래일 → 미국 세션일 변환
    private final TradingParallelRunner parallelRunner; // 계좌별 현재가 조회를 계좌 사이 병렬로
    private final ApplicationEventPublisher eventPublisher; // 조회 실패를 배치당 관리자 알림 1건으로 통지
    private final PrivacyTradePort privacyTradePort; // PRIVACY 기준 매매표 조회용

    // 배치 시작 시점 현재가 + 전일종가 + 기준 매매표(PRIVACY) 일괄 조회 결과 — executeBatch/placeOpenOrders 공통
    record PriceContext(
            List<StrategyTicker> cycleTickers,
            Map<PriceKey, PriceSnapshot> startPriceSnapshots,
            PrivacyTradeBase privacyBase
    ) {
        // 장전 점검(PrivacyBaseGuard) 결과로 기준표만 교체
        PriceContext withPrivacyBase(PrivacyTradeBase base) {
            return new PriceContext(cycleTickers, startPriceSnapshots, base);
        }
    }

    // 한 계좌의 현재가 조회 결과 — 요청 종목 중 prices에 없는 종목이 실패분
    private record AccountPrices(UUID accountId, List<StrategyTicker> tickers, Map<StrategyTicker, BigDecimal> prices) {
        List<StrategyTicker> failed() {
            return tickers.stream().filter(t -> !prices.containsKey(t)).toList();
        }
    }

    // 배치 시작 시점 현재가(계좌별) + 전일종가(공용 시세) + 기준 매매표(PRIVACY) 일괄 조회 — executeBatch/placeOpenOrders 공통
    // kstTradeDate: executeBatch는 today(당일), placeOpenOrders는 tradeDate(익일 KST 거래일)
    PriceContext loadPriceContext(List<BatchContext> contexts, LocalDate kstTradeDate) throws InterruptedException {
        List<StrategyTicker> cycleTickers = contexts.stream()
                .map(c -> c.strategy().ticker())
                .distinct().toList();
        List<AccountPrices> currents = fetchPerAccount(contexts);
        Map<StrategyTicker, BigDecimal> prevCloses = fetchPrevClosesBefore(cycleTickers, kstTradeDate);

        // 현재가 없는 키는 제외 — 호출부(collectCycleCandidate 등)가 키 부재를 이미 skip한다
        // 전일종가 실패 시 현재가로 대체 — 그 계좌의 현재가를 쓴다
        Map<PriceKey, PriceSnapshot> startPriceSnapshots = new HashMap<>();
        Set<StrategyTicker> prevFailed = new LinkedHashSet<>(); // 현재가로 대체한 종목 — 알림 1건에 묶는다
        for (AccountPrices account : currents) {
            account.prices().forEach((ticker, current) -> {
                BigDecimal prevClose = prevCloses.get(ticker);
                if (prevClose == null) prevFailed.add(ticker);
                startPriceSnapshots.put(new PriceKey(account.accountId(), ticker),
                        new PriceSnapshot(current, prevClose == null ? current : prevClose));
            });
        }

        // 현재가·전일종가 실패를 한 메시지로 통지 — 해당 없는 부분은 생략
        List<String> parts = new ArrayList<>();
        currentFailureMessage(currents).ifPresent(parts::add);
        if (!prevFailed.isEmpty()) parts.add(names(prevFailed) + " 전일종가 조회 실패(현재가로 대체)");
        publishAlert(parts);

        return new PriceContext(cycleTickers, startPriceSnapshots, privacyBaseFor(contexts, kstTradeDate));
    }

    // PRIVACY 전략이 있을 때만 기준 매매표 조회 — loadPriceContext와 리포트 재구성(reportRebuilt)이 공유
    PrivacyTradeBase privacyBaseFor(List<BatchContext> contexts, LocalDate kstTradeDate) {
        boolean hasPrivacy = contexts.stream().anyMatch(c -> c.strategy().requiresPrivacyBase());
        return hasPrivacy ? privacyTradePort.findTodayTrade(kstTradeDate).orElse(null) : null;
    }

    // 증권사 접수 직전 계좌별 현재가 재조회 — prevClose는 필요 없다(cap 판단은 현재가만 사용)
    Map<PriceKey, BigDecimal> reloadPlacementPrices(List<TradingCandidatePlanner.CycleState> states) throws InterruptedException {
        List<AccountPrices> currents = fetchPerAccount(states.stream().map(TradingCandidatePlanner.CycleState::ctx).toList());
        currentFailureMessage(currents).ifPresent(message -> publishAlert(List.of(message)));
        Map<PriceKey, BigDecimal> prices = new HashMap<>();
        currents.forEach(account -> account.prices().forEach((ticker, price) ->
                prices.put(new PriceKey(account.accountId(), ticker), price)));
        return prices;
    }

    // 한 계좌의 현재가만 필요한 경우 (수동 실행) — currentPrices 1회, 누락은 알림
    Map<StrategyTicker, BigDecimal> fetchPrices(List<StrategyTicker> tickers, BrokerAccountRef account) {
        AccountPrices result = fetchAccount(account.id(), account, tickers);
        currentFailureMessage(List.of(result)).ifPresent(message -> publishAlert(List.of(message)));
        return result.prices();
    }

    // 전일종가만 필요한 경우 (매매 미리보기 배치) — 공용 시세의 지금 기준 마지막 확정 종가
    // 관리자 알림 없음: 유일한 호출부인 미리보기는 readOnly 트랜잭션이라 EPR insert가 실패해 500이 되고,
    // 실패는 이후 StrategyOrderPlanBuilder 단건 재조회(BrokerCallGuard)가 사용자 응답(503 등)으로 직접 드러낸다
    Map<StrategyTicker, BigDecimal> fetchPrevCloses(List<StrategyTicker> tickers) {
        return BrokerCallGuard.tryGet("전일종가 조회", () -> marketData.latestCloses(tickers)).orElse(Map.of());
    }

    // 정규장 확정 종가 (마감 리포트 전용) — 그 세션의 확정 일봉 종가. 봉이 없는 종목은 키가 없다(현재가로 대체하지 않는다)
    // 알림 없음 — 누락 처리·알림은 호출부(마감 리포트)가 정한다
    Map<StrategyTicker, BigDecimal> fetchClosingPrices(List<StrategyTicker> tickers, LocalDate kstTradeDate) {
        LocalDate usSessionDate = marketCalendarPort.usSessionDateOf(kstTradeDate);
        Map<StrategyTicker, BrokerCandle> bars = BrokerCallGuard.tryGet("확정종가 조회",
                () -> marketData.confirmedBars(tickers, usSessionDate)).orElse(Map.of());
        Map<StrategyTicker, BigDecimal> closes = new HashMap<>();
        bars.forEach((ticker, bar) -> {
            if (bar != null && bar.close() != null) closes.put(ticker, bar.close());
        });
        return closes;
    }

    // 전일종가 1회 일괄 조회 — 예외면 전 종목 실패로 보고 빈 맵(호출부가 현재가로 대체·알림)
    private Map<StrategyTicker, BigDecimal> fetchPrevClosesBefore(List<StrategyTicker> tickers, LocalDate kstTradeDate) {
        if (tickers.isEmpty()) return Map.of();
        LocalDate usSessionDate = marketCalendarPort.usSessionDateOf(kstTradeDate);
        try {
            return marketData.closesBefore(tickers, usSessionDate);
        } catch (RuntimeException e) {
            log.warn("전일종가 조회 실패(세션 {}): {}", usSessionDate, e.getMessage());
            return Map.of();
        }
    }

    // 계좌별로 그 계좌 전략의 종목만 모아 조회 — 같은 종목이라도 계좌마다 따로, 계좌 사이는 병렬
    private List<AccountPrices> fetchPerAccount(List<BatchContext> contexts) throws InterruptedException {
        Map<UUID, List<BatchContext>> byAccount = contexts.stream()
                .collect(Collectors.groupingBy(c -> c.account().id(), LinkedHashMap::new, Collectors.toList()));
        List<TradingParallelRunner.Task<AccountPrices>> tasks = byAccount.values().stream()
                .map(group -> {
                    TradingAccount account = group.getFirst().account();
                    List<StrategyTicker> tickers = group.stream().map(c -> c.strategy().ticker()).distinct().toList();
                    return new TradingParallelRunner.Task<AccountPrices>(account.id(),
                            () -> Optional.of(fetchAccount(account.id(), account.brokerRef(), tickers)));
                })
                .toList();
        return parallelRunner.runAll(tasks);
    }

    // 한 계좌 currentPrices 1회 — 예외면 그 계좌 종목 전부 실패, null 값도 실패. 다른 출처로 대체하지 않는다
    private AccountPrices fetchAccount(UUID accountId, BrokerAccountRef ref, List<StrategyTicker> tickers) {
        Map<StrategyTicker, BigDecimal> prices = new HashMap<>();
        try {
            Map<StrategyTicker, BigDecimal> fetched = brokerPricePort.currentPrices(ref, tickers);
            if (fetched != null) {
                tickers.forEach(t -> {
                    BigDecimal price = fetched.get(t);
                    if (price != null) prices.put(t, price);
                });
            }
        } catch (RuntimeException e) {
            log.warn("[accountId={}] 현재가 조회 실패: {}", accountId, e.getMessage());
        }
        return new AccountPrices(accountId, tickers, prices);
    }

    // 현재가 실패 문구 — "현재가 조회 실패: 계좌 N개 [SOXL, TQQQ]", 실패 없으면 empty
    private static Optional<String> currentFailureMessage(Collection<AccountPrices> currents) {
        Set<StrategyTicker> failedTickers = new LinkedHashSet<>();
        int failedAccounts = 0;
        for (AccountPrices account : currents) {
            List<StrategyTicker> failed = account.failed();
            if (failed.isEmpty()) continue;
            failedAccounts++;
            failedTickers.addAll(failed);
        }
        return failedAccounts == 0 ? Optional.empty()
                : Optional.of("현재가 조회 실패: 계좌 " + failedAccounts + "개 " + names(failedTickers));
    }

    // 문구가 있으면 TradingErrorEvent 1건으로 묶어 발행 — 종목·계좌 수만큼 알림이 반복되는 것 방지
    private void publishAlert(List<String> parts) {
        if (!parts.isEmpty()) eventPublisher.publishEvent(new TradingErrorEvent(null, String.join(", ", parts)));
    }

    private static String names(Collection<StrategyTicker> tickers) {
        return tickers.stream().map(StrategyTicker::name).collect(Collectors.joining(", ", "[", "]"));
    }
}
