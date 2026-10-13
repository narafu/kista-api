package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.application.port.output.CandlePort;
import com.kista.tradingcore.broker.application.port.output.LiveBalancePort;
import com.kista.tradingcore.broker.application.port.output.SellableQuantityPort;
import com.kista.tradingcore.broker.domain.model.BrokerBalance;
import com.kista.tradingcore.broker.domain.model.BrokerCandle;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import com.kista.tradingcore.matching.domain.model.DcaDecision;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategy.OrderPlan;
import com.kista.tradingcore.matching.domain.strategy.DcaCycleOrderStrategy;
import com.kista.tradingcore.matching.domain.strategy.DcaSchedule;
import com.kista.tradingcore.trading.application.port.output.DcaExecutionPort;
import com.kista.tradingcore.trading.application.port.output.FearGreedIndexPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.StrategyDcaDetailPort;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.Strategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// DCA 배치 회차 계획 — 기존 주문 복구·timing·실행일·이력 판정 후 250봉 종가(신선도 검증·세션 캐시)·잔고·CNN 지수 조회 → DcaCycleOrderStrategy.decide → skip이면 이력·알림 기록
@Slf4j
@Component
@RequiredArgsConstructor
class DcaRoundPlanner {

    private static final int CANDLE_COUNT = 250;            // SMA 최대 200 + RSI 안정화 (spec 6.1)
    private static final int PREVIOUS_SESSION_SCAN_DAYS = 10; // 직전 개장일 역탐색 상한 (연휴 여유)

    private final MarketCalendarPort marketCalendarPort;
    private final CandlePort candlePort;
    private final SellableQuantityPort sellableQuantityPort;
    private final LiveBalancePort liveBalancePort;          // AVG_PRICE 조건용 브로커 실잔고
    private final FearGreedIndexPort fearGreedIndexPort;    // FEAR_GREED 조건용 CNN 지수
    private final OrderPort orderPort;
    private final StrategyDcaDetailPort dcaDetailPort;
    private final DcaExecutionPort dcaExecutionPort;
    private final DcaExecutionRecorder recorder;
    private final DcaCycleOrderStrategy dcaStrategy;
    // (ticker, US 세션) → 검증된 종가 — 세션 이전 확정 봉은 불변이라 배치·전략 간 공유, 실패는 캐시하지 않음
    private final Map<CandleKey, List<BigDecimal>> closeCache = new ConcurrentHashMap<>();
    // US 세션 → 직전 개장일 CNN 지수 — 세션당 1개, 실패는 캐시하지 않음 (spec 6.2)
    private final Map<LocalDate, BigDecimal> fearGreedCache = new ConcurrentHashMap<>();
    // 직전 US 개장일 세션 메모 — 일봉 신선도·공포탐욕 조회와 같은 세션의 전략들이 캘린더 스캔을 공유
    private final Map<LocalDate, LocalDate> previousOpenCache = new ConcurrentHashMap<>();

    private record CandleKey(StrategyTicker ticker, LocalDate usSession) {}

    // empty = 이번 배치에서 DCA 신규 주문 없음 (비실행일·다른 timing·이미 판정·skip)
    Optional<OrderPlan> plan(BatchContext ctx, LocalDate tradeDate, Set<OrderTiming> creatableTimings, List<Order> existingOrders) {
        Strategy strategy = ctx.strategy();
        DcaConfig config = dcaDetailPort.findActiveByStrategyId(strategy.id())
                .orElseThrow(() -> new IllegalStateException("DCA 설정이 없습니다: " + strategy.id()));

        if (dcaExecutionPort.find(strategy.id(), tradeDate).isPresent()) return Optional.empty(); // 이미 판정됨

        // 주문 저장 후 이력 기록 전 중단된 재개 — 기존 DCA 주문으로 ORDERED 복구 (다른 timing 배치에서도 복구되도록 timing 판정보다 먼저)
        Optional<Order> existing = existingOrders.stream().filter(o -> config.orderLeg().equals(o.orderLeg())).findFirst();
        if (existing.isPresent()) {
            recorder.recordOrdered(ctx, tradeDate, existing.get(), null);
            return Optional.empty();
        }

        if (!creatableTimings.contains(config.timing())) return Optional.empty();
        LocalDate usSession = marketCalendarPort.usSessionDateOf(tradeDate);
        if (!DcaSchedule.isExecutionDay(usSession, config, marketCalendarPort::isUsSessionOpen)) return Optional.empty();

        // PLANNED/PLACED 외 상태(FILLED 등)로 넘어간 당일 DCA 주문 — 이력만 누락된 경우 재주문하지 않고 복구
        Optional<Order> terminal = orderPort.findByStrategyId(strategy.id(), tradeDate, tradeDate).stream()
                .filter(o -> config.orderLeg().equals(o.orderLeg())).findFirst();
        if (terminal.isPresent()) {
            recorder.recordOrdered(ctx, tradeDate, terminal.get(), null);
            return Optional.empty();
        }

        DcaDecision decision = dcaStrategy.decide(new DcaCycleOrderStrategy.DcaInputs(config, strategy.ticker(), tradeDate,
                loadCloses(strategy.ticker(), usSession), loadSellable(ctx, config, tradeDate), loadHolding(ctx, config),
                loadFearGreed(config, usSession)));
        return switch (decision) {
            case DcaDecision.Planned p -> Optional.of(new OrderPlan(null, null, List.of(p.order())));
            case DcaDecision.Skipped s -> {
                recorder.recordSkipped(ctx, tradeDate, s.reason(), s.detail(), s.referencePrice());
                yield Optional.empty();
            }
        };
    }

    // 조회 US 세션 이전 확정 종가 250봉 — 실패·피드 지연(마지막 봉 ≠ 직전 개장일)이면 null(DATA_UNAVAILABLE)
    private List<BigDecimal> loadCloses(StrategyTicker ticker, LocalDate usSession) {
        CandleKey key = new CandleKey(ticker, usSession);
        List<BigDecimal> cached = closeCache.get(key);
        if (cached != null) return cached;
        try {
            List<BrokerCandle> candles = candlePort.getDailyCandlesBefore(ticker.name(), usSession, CANDLE_COUNT);
            LocalDate expected = previousOpenSession(usSession);
            if (candles.isEmpty() || expected == null || !candles.getLast().date().equals(expected)) {
                log.warn("[DCA] 일봉 피드 지연·부족: ticker={}, usSession={}, 기대={}, 마지막={}",
                        ticker, usSession, expected, candles.isEmpty() ? null : candles.getLast().date());
                return null;
            }
            List<BigDecimal> closes = candles.stream().map(BrokerCandle::close).toList();
            closeCache.keySet().removeIf(k -> !k.usSession().equals(usSession)); // 지난 세션 제거 — 맵 크기 ≤ 티커 수
            closeCache.put(key, closes);
            return closes;
        } catch (RuntimeException e) {
            log.warn("[DCA] 일봉 조회 실패: ticker={}, usSession={}", ticker, usSession, e);
            return null;
        }
    }

    // usSession 직전 US 개장일 — 휴장 데이터 미존재 등으로 못 찾으면 null
    private LocalDate previousOpenSession(LocalDate usSession) {
        LocalDate cached = previousOpenCache.get(usSession);
        if (cached != null) return cached;
        for (int i = 1; i <= PREVIOUS_SESSION_SCAN_DAYS; i++) {
            LocalDate d = usSession.minusDays(i);
            if (marketCalendarPort.isUsSessionOpen(d)) {
                previousOpenCache.keySet().removeIf(k -> !k.equals(usSession)); // 지난 세션 제거 — 맵 크기 ≤ 1
                previousOpenCache.put(usSession, d);
                return d;
            }
        }
        return null;
    }

    // FEAR_GREED 조건이 있을 때만 직전 개장일 CNN 지수 — 세션 메모 캐시, 실패·직전 개장일 미확인이면 null(DATA_UNAVAILABLE)
    private BigDecimal loadFearGreed(DcaConfig config, LocalDate usSession) {
        if (!config.hasIndicator(DcaIndicator.FEAR_GREED)) return null;
        BigDecimal cached = fearGreedCache.get(usSession);
        if (cached != null) return cached;
        LocalDate previousOpen = previousOpenSession(usSession);
        if (previousOpen == null) return null;
        Optional<BigDecimal> value = fearGreedIndexPort.closeOn(previousOpen);
        if (value.isEmpty()) return null;
        fearGreedCache.keySet().removeIf(k -> !k.equals(usSession)); // 지난 세션 제거 — 맵 크기 ≤ 1
        fearGreedCache.put(usSession, value.get());
        return value.get();
    }

    // AVG_PRICE 조건이 있을 때만 브로커 실잔고(계좌+티커 합산) — 실패 시 null(DATA_UNAVAILABLE)
    private DcaCycleOrderStrategy.DcaInputs.Holding loadHolding(BatchContext ctx, DcaConfig config) {
        if (!config.hasIndicator(DcaIndicator.AVG_PRICE)) return null;
        try {
            BrokerBalance b = liveBalancePort.getLiveBalance(ctx.account().brokerRef(), ctx.strategy().ticker());
            return new DcaCycleOrderStrategy.DcaInputs.Holding(b.holdings(), b.avgPrice());
        } catch (RuntimeException e) {
            log.warn("[DCA] 실잔고 조회 실패: strategy={}", ctx.strategy().id(), e);
            return null;
        }
    }

    // SELL만 — 판매가능수량 − 같은 계좌·거래일·종목 기존 PLANNED/PLACED SELL 예약분, 실패 시 null
    private Integer loadSellable(BatchContext ctx, DcaConfig config, LocalDate tradeDate) {
        if (config.direction() != OrderDirection.SELL) return null;
        try {
            int sellable = sellableQuantityPort.getSellableQuantity(ctx.strategy().ticker(), ctx.account().brokerRef()).quantity();
            int reserved = orderPort.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(
                    ctx.account().id(), tradeDate, ctx.strategy().ticker());
            return Math.max(0, sellable - reserved);
        } catch (RuntimeException e) {
            log.warn("[DCA] 판매가능수량 조회 실패: strategy={}", ctx.strategy().id(), e);
            return null;
        }
    }
}
