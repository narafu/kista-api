package com.kista.trading.application.service;

import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.broker.domain.model.PriceSnapshot;
import com.kista.privacy.application.port.output.PrivacyTradePort;
import com.kista.privacy.domain.model.PrivacyTradeBase;
import com.kista.sharedkernel.Broker;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.trading.application.event.TradingErrorEvent;
import com.kista.trading.domain.model.BatchContext;
import com.kista.trading.domain.model.TradingAccount;
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

// 복수종목 현재가 일괄 조회 + 누락분 단건 fallback + 배치 시작 시점 가격 컨텍스트 조립
@Component
@RequiredArgsConstructor
@Slf4j
class TradingPriceFetcher {

    private final BrokerPricePort brokerPricePort;
    private final ApplicationEventPublisher eventPublisher; // 일괄+단건 fallback 모두 실패한 종목을 관리자에게 이벤트로 통지
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
        Map<StrategyTicker, PriceSnapshot> startPriceSnapshots = fetchPriceSnapshots(cycleTickers, priceAccount);

        // 기준 매매표 조회 (PRIVACY)
        boolean hasPrivacy = contexts.stream().anyMatch(c -> c.strategy().isPrivacy());
        PrivacyTradeBase privacyBase = hasPrivacy
                ? privacyTradePort.findTodayTrade(date).orElse(null)
                : null;

        return new PriceContext(cycleTickers, priceAccount, startPriceSnapshots, privacyBase);
    }

    // 증권사 접수 직전 ticker별 현재가 일괄 재조회 — fetchPrices가 ticker당 1회 배치 조회를 보장
    // prevClose는 필요 없으므로(cap 판단은 현재가만 사용) fetchPriceSnapshots가 아닌 fetchPrices 사용
    Map<StrategyTicker, BigDecimal> reloadPlacementPrices(List<TradingCandidatePlanner.CycleState> states) {
        List<StrategyTicker> tickers = states.stream()
                .map(state -> state.ctx().strategy().ticker())
                .distinct().toList();
        BrokerAccountRef priceAccount = selectPriceAccount(states.stream().map(TradingCandidatePlanner.CycleState::ctx).toList());
        return fetchPrices(tickers, priceAccount);
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

    // 현재가만 필요한 경우 (종가 조회 등)
    Map<StrategyTicker, BigDecimal> fetchPrices(List<StrategyTicker> tickers, BrokerAccountRef account) {
        return fetchWithFallback(tickers, account, "현재가", true,
                (t, acc) -> brokerPricePort.getPrices(t, acc),
                (t, acc) -> brokerPricePort.getPrice(t, acc));
    }

    // 현재가 + 전일종가 함께 필요한 경우 (0회차 진입 방향 판단)
    Map<StrategyTicker, PriceSnapshot> fetchPriceSnapshots(List<StrategyTicker> tickers, BrokerAccountRef account) {
        Map<StrategyTicker, PriceSnapshot> snapshots = fetchWithFallback(tickers, account, "스냅샷", true,
                (t, acc) -> brokerPricePort.getPriceSnapshots(t, acc),
                (t, acc) -> brokerPricePort.getPriceSnapshot(t, acc));
        // snap==null(일괄+단건 fallback 모두 실패)인 종목은 제외 — 호출부(collectCycleCandidate 등)가 맵에 키 부재를 이미 null-tolerant하게 처리함
        snapshots.entrySet().removeIf(entry -> entry.getValue() == null);
        return snapshots;
    }

    // 전일종가만 필요한 경우 (매매 미리보기 배치 등) — 종목 수만큼 순차 단건 조회 대신 1회 일괄 조회
    // 관리자 알림 없음: 유일한 호출부인 미리보기는 readOnly 트랜잭션이라 EPR insert가 실패해 500이 되고,
    // 실패는 이후 StrategyOrderPlanBuilder 단건 재조회(BrokerCallGuard)가 사용자 응답(503 등)으로 직접 드러낸다
    Map<StrategyTicker, BigDecimal> fetchPrevCloses(List<StrategyTicker> tickers, BrokerAccountRef account) {
        return fetchWithFallback(tickers, account, "전일종가", false,
                (t, acc) -> brokerPricePort.getPrevCloses(t, acc),
                (t, acc) -> brokerPricePort.getPrevClose(t, acc));
    }

    // 정규장 확정 종가만 필요한 경우 (마감 리포트 전용)
    Map<StrategyTicker, BigDecimal> fetchClosingPrices(List<StrategyTicker> tickers, LocalDate tradeDate, BrokerAccountRef account) {
        return fetchWithFallback(tickers, account, "확정종가", true,
                (t, acc) -> brokerPricePort.getClosingPrices(t, tradeDate, acc),
                (t, acc) -> brokerPricePort.getClosingPrice(t, tradeDate, acc));
    }

    // 복수종목 일괄 조회 실패(또는 일부 누락) 시 종목별 단건 fallback — 두 메서드 공용 골격
    // alertOnFailure: 일괄+단건 모두 실패 시 TradingErrorEvent 발행 여부 (readOnly 트랜잭션 호출부는 false)
    private <T> Map<StrategyTicker, T> fetchWithFallback(List<StrategyTicker> tickers, BrokerAccountRef account, String label,
                                                  boolean alertOnFailure,
                                                  BiFunction<List<StrategyTicker>, BrokerAccountRef, Map<StrategyTicker, T>> bulkFetch,
                                                  BiFunction<StrategyTicker, BrokerAccountRef, T> singleFetch) {
        Map<StrategyTicker, T> result;
        try {
            result = new HashMap<>(bulkFetch.apply(tickers, account));
        } catch (Exception e) {
            log.warn("복수종목 {} 일괄 조회 실패, 단건 fallback 사용: {}", label, e.getMessage());
            result = new HashMap<>();
        }
        // 일괄+단건 fallback 모두 실패한 종목을 모아 알림 1건으로 통지 (실패 종목 수만큼 알림이 반복 발송되는 것 방지)
        List<String> failedTickers = new ArrayList<>();
        for (StrategyTicker ticker : tickers) {
            // containsKey가 아닌 값 null 체크 — bulkFetch가 특정 ticker에 null 값을 담아 반환해도 단건 fallback으로 재조회
            if (result.get(ticker) == null) {
                try {
                    result.put(ticker, singleFetch.apply(ticker, account));
                } catch (Exception e) {
                    log.warn("[{}] 단건 {} 조회 실패: {}", ticker.name(), label, e.getMessage());
                    failedTickers.add(ticker.name());
                }
            }
        }
        if (alertOnFailure && !failedTickers.isEmpty()) {
            eventPublisher.publishEvent(new TradingErrorEvent(null,
                    failedTickers + " " + label + " 조회 실패(일괄+단건 모두 실패)"));
        }
        return result;
    }
}
