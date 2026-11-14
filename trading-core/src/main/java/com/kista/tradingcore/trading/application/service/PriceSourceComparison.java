package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.adapter.out.marketdata.AlpacaMarketDataAdapter;
import com.kista.tradingcore.broker.application.port.output.BrokerPricePort;
import com.kista.tradingcore.broker.application.service.BrokerCallGuard;
import com.kista.tradingcore.broker.domain.model.BrokerCandle;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.TradingAccount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 14 T2 병행 비교용 임시 코드 — T3에서 삭제. 배치 동작은 그대로 두고 새 출처 값을 따로 조회해 로그로만 비교한다
@Slf4j
@Component
class PriceSourceComparison {

    private final AlpacaMarketDataAdapter alpaca;       // 전일 종가·확정 종가의 새 출처
    private final BrokerPricePort brokerPricePort;      // 현재가의 새 출처(계좌 증권사 직접 조회)
    private final Duration closeRecheckDelay;           // close+10 조회 뒤 close+20 조회까지 대기(운영 10분, 테스트 ZERO)

    @Autowired
    PriceSourceComparison(AlpacaMarketDataAdapter alpaca, BrokerPricePort brokerPricePort) {
        this(alpaca, brokerPricePort, Duration.ofMinutes(10));
    }

    PriceSourceComparison(AlpacaMarketDataAdapter alpaca, BrokerPricePort brokerPricePort, Duration closeRecheckDelay) {
        this.alpaca = alpaca;
        this.brokerPricePort = brokerPricePort;
        this.closeRecheckDelay = closeRecheckDelay;
    }

    // 가격 컨텍스트 직후: 전일 종가(Alpaca)와 현재가(메리츠 계좌) 비교
    void afterLoad(List<BatchContext> contexts, TradingPriceFetcher.PriceContext priceCtx, LocalDate usSession) {
        guarded("전일종가", () -> {
            Map<StrategyTicker, BigDecimal> old = new HashMap<>();
            priceCtx.startPriceSnapshots().forEach((t, snap) -> old.put(t, snap.prevClose()));
            logAll("prevClose", priceCtx.cycleTickers(), old, alpaca.closesBefore(priceCtx.cycleTickers(), usSession));
        });
        Map<StrategyTicker, BigDecimal> currents = new HashMap<>();
        priceCtx.startPriceSnapshots().forEach((t, snap) -> currents.put(t, snap.current()));
        current(contexts, currents);
    }

    // 현재가 비교: 메리츠(MOCK 제외) 계좌 중 id 최소(UUID v7 = 생성순) 하나의 계좌가 가진 종목만 그 계좌로 조회
    void current(List<BatchContext> contexts, Map<StrategyTicker, BigDecimal> old) {
        guarded("현재가", () -> {
            // 메리츠 계좌 하나 선택 — 없으면 비교 생략
            TradingAccount account = contexts.stream().map(BatchContext::account)
                    .filter(a -> a.broker() == Broker.MERITZ)
                    .min(Comparator.comparing(TradingAccount::id)).orElse(null);
            if (account == null) return;
            List<StrategyTicker> tickers = contexts.stream()
                    .filter(c -> c.account().id().equals(account.id()))
                    .map(c -> c.strategy().ticker()).distinct().toList();
            logAll("current", tickers, old, brokerPricePort.currentPrices(account.brokerRef(), tickers));
        });
    }

    // 확정 종가 비교: 가상 스레드 하나에서 close+10 조회 → 대기 → close+20 조회 — 배치 스레드는 바로 돌아온다
    void closes(List<StrategyTicker> tickers, Map<StrategyTicker, BigDecimal> old, LocalDate usSession) {
        // 스레드에 넘기기 전에 복사 — null 값은 거른다(old 없음은 맵 부재로 표현)
        Map<StrategyTicker, BigDecimal> oldCopy = new HashMap<>();
        old.forEach((t, v) -> { if (v != null) oldCopy.put(t, v); });
        List<StrategyTicker> targets = List.copyOf(tickers);
        Map<StrategyTicker, BigDecimal> frozen = Map.copyOf(oldCopy);
        Thread.ofVirtual().start(() -> {
            guarded("확정종가+10", () -> logAll("close+10", targets, frozen, confirmedCloses(targets, usSession)));
            try {
                Thread.sleep(closeRecheckDelay);
            } catch (InterruptedException e) {
                return; // 종료·인터럽트면 조용히 끝낸다
            }
            guarded("확정종가+20", () -> logAll("close+20", targets, frozen, confirmedCloses(targets, usSession)));
        });
    }

    private Map<StrategyTicker, BigDecimal> confirmedCloses(Collection<StrategyTicker> tickers, LocalDate usSession) {
        Map<StrategyTicker, BigDecimal> result = new HashMap<>();
        Map<StrategyTicker, BrokerCandle> bars = alpaca.confirmedBars(tickers, usSession);
        bars.forEach((t, bar) -> result.put(t, bar == null ? null : bar.close()));
        return result;
    }

    // 종목마다 한 줄 — 어느 한쪽이 없으면 그 칸과 diffPct를 null로 남긴다
    private void logAll(String kind, Collection<StrategyTicker> tickers, Map<StrategyTicker, BigDecimal> old, Map<StrategyTicker, BigDecimal> now) {
        for (StrategyTicker t : tickers) {
            BigDecimal o = old.get(t);
            BigDecimal n = now.get(t);
            log.info("[시세 비교] ticker={} kind={} old={} new={} diffPct={}", t.name(), kind, o, n, diffPct(o, n));
        }
    }

    private static BigDecimal diffPct(BigDecimal old, BigDecimal now) {
        if (old == null || now == null || old.signum() == 0) return null;
        return now.subtract(old).multiply(BigDecimal.valueOf(100)).divide(old, 4, RoundingMode.HALF_UP);
    }

    // 비교의 어떤 예외도 배치로 전파하지 않는다
    private static void guarded(String label, Runnable body) {
        BrokerCallGuard.tryGet("시세 비교 " + label, () -> {
            body.run();
            return Boolean.TRUE;
        });
    }
}
