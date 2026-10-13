package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.PriceSnapshot;
import com.kista.tradingcore.privacy.application.port.output.PrivacyTradePort;
import com.kista.tradingcore.privacy.domain.model.PrivacyTradeBase;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.trading.application.event.TradingErrorEvent;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.TradingAccount;
import com.kista.tradingcore.broker.application.port.output.BrokerPricePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

// 복수종목 시세 일괄 조회 + 누락분 단건 fallback (관리자 → 계좌 체인은 BrokerRouter가 처리) + 배치 시작 시점 가격 컨텍스트 조립
@Component
@RequiredArgsConstructor
@Slf4j
class TradingPriceFetcher {

    private final BrokerPricePort brokerPricePort;
    private final ApplicationEventPublisher eventPublisher; // 관리자·계좌 모든 단계가 실패한 종목을 관리자에게 이벤트로 통지
    private final PrivacyTradePort privacyTradePort; // loadPriceContext의 PRIVACY 기준 매매표 조회용

    // 배치 시작 시점 현재가 + 전일종가 + 기준 매매표(PRIVACY) 일괄 조회 결과 — executeBatch/placeOpenOrders 공통
    record PriceContext(
            List<StrategyTicker> cycleTickers,
            BrokerAccountRef priceAccount,
            Map<StrategyTicker, PriceSnapshot> startPriceSnapshots,
            PrivacyTradeBase privacyBase
    ) {
        // 장전 점검(PrivacyBaseGuard) 결과로 기준표만 교체
        PriceContext withPrivacyBase(PrivacyTradeBase base) {
            return new PriceContext(cycleTickers, priceAccount, startPriceSnapshots, base);
        }
    }

    // 배치 시작 시점 현재가 + 전일종가 + 기준 매매표(PRIVACY) 일괄 조회 — executeBatch/placeOpenOrders 공통
    // date: executeBatch는 today(당일), placeOpenOrders는 tradeDate(익일 US 거래일)
    PriceContext loadPriceContext(List<BatchContext> contexts, LocalDate date) {
        List<StrategyTicker> cycleTickers = contexts.stream()
                .map(c -> c.strategy().ticker())
                .distinct().toList();
        BrokerAccountRef priceAccount = selectPriceAccount(contexts); // Toss 계좌 우선
        Set<StrategyTicker> currentFailed = new LinkedHashSet<>(); // 현재가를 끝내 못 구한 종목 — 알림 1건으로 묶는다
        Map<StrategyTicker, BigDecimal> currents = fetchCurrentPrices(cycleTickers, priceAccount, currentFailed);
        // 현재가가 없는 종목은 어차피 제외되므로 전일종가 조회 대상에서 뺀다
        List<StrategyTicker> prevTargets = cycleTickers.stream().filter(t -> !currentFailed.contains(t)).toList();
        Set<StrategyTicker> prevFailed = new LinkedHashSet<>();
        Map<StrategyTicker, BigDecimal> prevCloses = prevTargets.isEmpty() ? Map.of()
                : fetchWithFallback(prevTargets, priceAccount, "전일종가", prevFailed,
                        (t, acc) -> brokerPricePort.getPrevCloses(t, acc),
                        (t, acc) -> brokerPricePort.getPrevClose(t, acc));
        alertLoadFailed(currentFailed, prevFailed);

        // current==null(모든 단계 실패)인 종목은 제외 — 호출부(collectCycleCandidate 등)가 맵에 키 부재를 이미 null-tolerant하게 처리함
        // prevClose만 없으면 current로 대체(어댑터의 스냅샷 폴백과 동일한 의미)
        Map<StrategyTicker, PriceSnapshot> startPriceSnapshots = new HashMap<>();
        currents.forEach((ticker, current) -> {
            if (current != null) {
                startPriceSnapshots.put(ticker, new PriceSnapshot(current, prevCloses.getOrDefault(ticker, current)));
            }
        });

        // 기준 매매표 조회 (PRIVACY)
        boolean hasPrivacy = contexts.stream().anyMatch(c -> c.strategy().isPrivacy());
        PrivacyTradeBase privacyBase = hasPrivacy
                ? privacyTradePort.findTodayTrade(date).orElse(null)
                : null;

        return new PriceContext(cycleTickers, priceAccount, startPriceSnapshots, privacyBase);
    }

    // 증권사 접수 직전 ticker별 현재가 일괄 재조회
    // prevClose는 필요 없으므로(cap 판단은 현재가만 사용) 현재가 경로만 사용
    Map<StrategyTicker, BigDecimal> reloadPlacementPrices(List<TradingCandidatePlanner.CycleState> states) {
        List<StrategyTicker> tickers = states.stream()
                .map(state -> state.ctx().strategy().ticker())
                .distinct().toList();
        BrokerAccountRef priceAccount = selectPriceAccount(states.stream().map(TradingCandidatePlanner.CycleState::ctx).toList());
        Set<StrategyTicker> failed = new LinkedHashSet<>();
        Map<StrategyTicker, BigDecimal> prices = fetchCurrentPrices(tickers, priceAccount, failed);
        alertFailed(failed, "현재가", "관리자·계좌 모두 실패");
        return prices;
    }

    // 현재가·전일종가 실패를 한 메시지로 통지 — 해당 없는 부분은 생략
    private void alertLoadFailed(Set<StrategyTicker> currentFailed, Set<StrategyTicker> prevFailed) {
        List<String> parts = new ArrayList<>();
        if (!currentFailed.isEmpty()) parts.add(names(currentFailed) + " 현재가 조회 실패");
        if (!prevFailed.isEmpty()) parts.add(names(prevFailed) + " 전일종가 조회 실패(현재가로 대체)");
        if (!parts.isEmpty()) {
            eventPublisher.publishEvent(new TradingErrorEvent(null, String.join(", ", parts) + " — 관리자·계좌 모두 실패"));
        }
    }

    private static String names(Set<StrategyTicker> tickers) {
        return tickers.stream().map(StrategyTicker::name).collect(Collectors.joining(", ", "[", "]"));
    }

    // 가격 조회에 사용할 계좌 선택 — Toss 계좌가 있으면 우선 사용 (토스 시세 API 일관성)
    BrokerAccountRef selectPriceAccount(List<BatchContext> contexts) {
        return contexts.stream()
                .map(BatchContext::account)
                .filter(account -> account.broker() == Broker.TOSS)
                .map(TradingAccount::brokerRef)
                .findFirst()
                .orElseGet(() -> contexts.getFirst().account().brokerRef());
    }

    // 현재가만 필요한 경우 (수동 실행)
    Map<StrategyTicker, BigDecimal> fetchPrices(List<StrategyTicker> tickers, BrokerAccountRef account) {
        Set<StrategyTicker> failed = new LinkedHashSet<>();
        Map<StrategyTicker, BigDecimal> prices = fetchCurrentPrices(tickers, account, failed);
        alertFailed(failed, "현재가", "관리자·계좌 모두 실패");
        return prices;
    }

    // 전일종가만 필요한 경우 (매매 미리보기 배치 등) — 종목 수만큼 순차 단건 조회 대신 1회 일괄 조회
    // 관리자 알림 없음: 유일한 호출부인 미리보기는 readOnly 트랜잭션이라 EPR insert가 실패해 500이 되고,
    // 실패는 이후 StrategyOrderPlanBuilder 단건 재조회(BrokerCallGuard)가 사용자 응답(503 등)으로 직접 드러낸다
    Map<StrategyTicker, BigDecimal> fetchPrevCloses(List<StrategyTicker> tickers, BrokerAccountRef account) {
        return fetchWithFallback(tickers, account, "전일종가", new LinkedHashSet<>(),
                (t, acc) -> brokerPricePort.getPrevCloses(t, acc),
                (t, acc) -> brokerPricePort.getPrevClose(t, acc));
    }

    // 정규장 확정 종가만 필요한 경우 (마감 리포트 전용)
    Map<StrategyTicker, BigDecimal> fetchClosingPrices(List<StrategyTicker> tickers, LocalDate tradeDate, BrokerAccountRef account) {
        Set<StrategyTicker> failed = new LinkedHashSet<>();
        Map<StrategyTicker, BigDecimal> prices = fetchWithFallback(tickers, account, "확정종가", failed,
                (t, acc) -> brokerPricePort.getClosingPrices(t, tradeDate, acc),
                (t, acc) -> brokerPricePort.getClosingPrice(t, tradeDate, acc));
        alertFailed(failed, "확정종가", "관리자·계좌 모두 실패");
        return prices;
    }

    // 현재가 일괄 조회 → 누락분 단건 fallback (BrokerRouter가 관리자 → 계좌 순으로 처리)
    private Map<StrategyTicker, BigDecimal> fetchCurrentPrices(List<StrategyTicker> tickers, BrokerAccountRef account,
                                                               Set<StrategyTicker> failed) {
        return fetchWithFallback(tickers, account, "현재가", failed,
                (t, acc) -> brokerPricePort.getPrices(t, acc),
                (t, acc) -> brokerPricePort.getPrice(t, acc));
    }

    // 실패 종목이 있으면 TradingErrorEvent 1건으로 통지 (종목·단계 수만큼 알림이 반복 발송되는 것 방지)
    private void alertFailed(Set<StrategyTicker> failed, String label, String scope) {
        if (!failed.isEmpty()) {
            eventPublisher.publishEvent(new TradingErrorEvent(null,
                    failed.stream().map(StrategyTicker::name).toList() + " " + label + " 조회 실패(" + scope + ")"));
        }
    }

    // 복수종목 일괄 조회 실패(또는 일부 누락) 시 종목별 단건 fallback — 공용 골격
    // failed: 일괄+단건 모두 실패한 종목을 모으는 출력 인자 (알림 여부·묶음은 호출부가 결정)
    private <T> Map<StrategyTicker, T> fetchWithFallback(List<StrategyTicker> tickers, BrokerAccountRef account, String label,
                                                  Set<StrategyTicker> failed,
                                                  BiFunction<List<StrategyTicker>, BrokerAccountRef, Map<StrategyTicker, T>> bulkFetch,
                                                  BiFunction<StrategyTicker, BrokerAccountRef, T> singleFetch) {
        Map<StrategyTicker, T> result;
        try {
            result = new HashMap<>(bulkFetch.apply(tickers, account));
        } catch (Exception e) {
            log.warn("복수종목 {} 일괄 조회 실패, 단건 fallback 사용: {}", label, e.getMessage());
            result = new HashMap<>();
        }
        for (StrategyTicker ticker : tickers) {
            // containsKey가 아닌 값 null 체크 — bulkFetch가 특정 ticker에 null 값을 담아 반환해도 단건 fallback으로 재조회
            if (result.get(ticker) == null) {
                try {
                    T value = singleFetch.apply(ticker, account);
                    if (value != null) {
                        result.put(ticker, value);
                    } else {
                        failed.add(ticker);
                    }
                } catch (Exception e) {
                    log.warn("[{}] 단건 {} 조회 실패: {}", ticker.name(), label, e.getMessage());
                    failed.add(ticker);
                }
            }
        }
        return result;
    }
}
