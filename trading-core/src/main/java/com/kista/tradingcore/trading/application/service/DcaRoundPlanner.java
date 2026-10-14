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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

// DCA 배치 회차 계획 — 기존 주문 복구·timing·실행일·이력 판정 후 250봉 종가(신선도 검증·세션 캐시)·잔고·CNN 지수 조회 → DcaCycleOrderStrategy.decide → skip이면 이력·알림 기록
@Slf4j
@Component
@RequiredArgsConstructor
class DcaRoundPlanner {

    private static final int CANDLE_COUNT = 250;            // SMA 최대 200 + RSI 안정화 (spec 6.1)
    private static final int PREVIOUS_SESSION_SCAN_DAYS = 10; // 직전 개장일 역탐색 상한 (연휴 여유)
    private static final Duration FEAR_GREED_TTL = Duration.ofMinutes(10); // 배치 1회를 덮는 길이 — 10분 안의 재계획(수동 실행 포함)은 같은 값

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
    // 최신 CNN 지수 단기 메모 — 같은 배치의 전략들이 공유, TTL 경과·실패 시 재조회 (spec 6.2)
    private final AtomicReference<FearGreedMemo> fearGreedMemo = new AtomicReference<>();
    // 직전 US 개장일 세션 메모 — 일봉 신선도 검증에서 같은 세션의 전략들이 캘린더 스캔을 공유
    private final Map<LocalDate, LocalDate> previousOpenCache = new ConcurrentHashMap<>();

    private record CandleKey(StrategyTicker ticker, LocalDate usSession) {}

    private record FearGreedMemo(BigDecimal value, Instant fetchedAt) {}

    // empty = 이번 배치에서 DCA 신규 주문 없음 (비실행일·다른 timing·이미 판정·skip)
    // currentPrice: 배치 시작 시점 현재가(null 허용) — BUY 기준가 max(전일 종가, 현재가) 산정용
    Optional<DcaDecision.Planned> plan(BatchContext ctx, LocalDate tradeDate, Set<OrderTiming> creatableTimings,
                                       List<Order> existingOrders, BigDecimal currentPrice) {
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
                loadFearGreed(config), currentPrice));
        return switch (decision) {
            case DcaDecision.Planned p -> {
                log.info("[DCA] 회차 계획: strategy={}, 현재가={}, 기준가={}, 수량={}, 지정가={}",
                        strategy.id(), currentPrice, p.referencePrice(), p.order().quantity(), p.order().price());
                yield Optional.of(p);
            }
            case DcaDecision.Skipped s -> {
                recorder.recordSkipped(ctx, tradeDate, s.reason(), s.detail(), s.referencePrice());
                yield Optional.empty();
            }
        };
    }

    // 조회 US 세션 이전 확정 종가 250봉 — 실패·피드 지연(마지막 봉 ≠ 직전 개장일)이면 null(DATA_UNAVAILABLE)
    private List<BigDecimal> loadCloses(StrategyTicker ticker, LocalDate usSession) {
        closeCache.keySet().removeIf(k -> !k.usSession().equals(usSession)); // 지난 세션 제거 — 맵 크기 ≤ 티커 수
        // computeIfAbsent — 병렬 그룹이 같은 키를 동시에 조회해도 피드 호출 1회, null(실패)은 저장되지 않는다
        return closeCache.computeIfAbsent(new CandleKey(ticker, usSession), k -> fetchCloses(ticker, usSession));
    }

    private List<BigDecimal> fetchCloses(StrategyTicker ticker, LocalDate usSession) {
        try {
            List<BrokerCandle> candles = candlePort.getDailyCandlesBefore(ticker.name(), usSession, CANDLE_COUNT);
            LocalDate expected = previousOpenSession(usSession);
            if (candles.isEmpty() || expected == null || !candles.getLast().date().equals(expected)) {
                log.warn("[DCA] 일봉 피드 지연·부족: ticker={}, usSession={}, 기대={}, 마지막={}",
                        ticker, usSession, expected, candles.isEmpty() ? null : candles.getLast().date());
                return null;
            }
            return candles.stream().map(BrokerCandle::close).toList();
        } catch (RuntimeException e) {
            log.warn("[DCA] 일봉 조회 실패: ticker={}, usSession={}", ticker, usSession, e);
            return null;
        }
    }

    // usSession 직전 US 개장일 — 휴장 데이터 미존재 등으로 못 찾으면 null
    private LocalDate previousOpenSession(LocalDate usSession) {
        previousOpenCache.keySet().removeIf(k -> !k.equals(usSession)); // 지난 세션 제거 — 맵 크기 ≤ 1
        return previousOpenCache.computeIfAbsent(usSession, this::scanPreviousOpen);
    }

    private LocalDate scanPreviousOpen(LocalDate usSession) {
        for (int i = 1; i <= PREVIOUS_SESSION_SCAN_DAYS; i++) {
            LocalDate d = usSession.minusDays(i);
            if (marketCalendarPort.isUsSessionOpen(d)) return d;
        }
        return null;
    }

    // FEAR_GREED 조건이 있을 때만 배치 시점 최신 CNN 지수 — TTL 메모, 실패면 null(DATA_UNAVAILABLE)·캐시 안 함
    // ponytail: 병렬 그룹이 동시에 만료를 보면 CNN을 중복 호출할 수 있다 — 호출 수가 문제되면 락으로 감쌀 것
    private BigDecimal loadFearGreed(DcaConfig config) {
        if (!config.hasIndicator(DcaIndicator.FEAR_GREED)) return null;
        FearGreedMemo memo = fearGreedMemo.get();
        Instant now = Instant.now();
        if (memo != null && memo.fetchedAt().plus(FEAR_GREED_TTL).isAfter(now)) return memo.value();
        BigDecimal value = fearGreedIndexPort.latest().orElse(null);
        if (value != null) fearGreedMemo.set(new FearGreedMemo(value, now));
        return value;
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
