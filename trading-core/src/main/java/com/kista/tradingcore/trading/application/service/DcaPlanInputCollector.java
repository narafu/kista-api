package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.application.port.output.CandlePort;
import com.kista.tradingcore.broker.application.port.output.SellableQuantityPort;
import com.kista.tradingcore.broker.domain.model.BrokerCandle;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.matching.domain.model.DcaRule;
import com.kista.tradingcore.matching.domain.model.DcaStrategyConfig;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategy;
import com.kista.tradingcore.matching.domain.strategy.DcaCycleOrderStrategy;
import com.kista.tradingcore.matching.domain.strategy.DcaSchedule;
import com.kista.tradingcore.trading.application.port.output.DcaExecutionPort;
import com.kista.tradingcore.trading.application.port.output.FearGreedIndexPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.StrategyDcaDetailPort;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.application.service.support.UnassignedUsdCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

// 주식 DCA plan() 입력 수집 — 규칙마다 판정 이력·기존 주문 복구·timing·실행일 확인 후 250봉 종가(신선도 검증·세션 캐시)·
// 판매가능수량·CNN 지수를 모은다. 부작용 없음(이력 기록 안 함) — 프리뷰도 이 경로를 타며, 복구 기록은 배치(TradingCandidatePlanner)가 한다
@Slf4j
@Component
@RequiredArgsConstructor
class DcaPlanInputCollector {

    private static final int CANDLE_COUNT = 250;            // SMA 최대 200 + RSI 안정화 (spec 6.1)
    private static final int PREVIOUS_SESSION_SCAN_DAYS = 10; // 직전 개장일 역탐색 상한 (연휴 여유)
    private static final Duration FEAR_GREED_TTL = Duration.ofMinutes(10); // 배치 1회를 덮는 길이 — 10분 안의 재계획(수동 실행 포함)은 같은 값

    private final MarketCalendarPort marketCalendarPort;
    private final CandlePort candlePort;
    private final SellableQuantityPort sellableQuantityPort; // 주식 SELL 상한 — 증권사 판매가능수량
    private final FearGreedIndexPort fearGreedIndexPort;    // FEAR_GREED 조건용 CNN 지수
    private final OrderPort orderPort;
    private final StrategyDcaDetailPort dcaDetailPort;
    private final DcaExecutionPort dcaExecutionPort;        // 판정 완료 여부 조회 전용(읽기)
    private final UnassignedUsdCalculator unassignedUsd;    // 주식 BUY 가용 USD — 계좌 미배정 현금
    // (ticker, US 세션) → 검증된 종가 — 세션 이전 확정 봉은 불변이라 배치·전략 간 공유, 실패는 캐시하지 않음
    private final Map<CandleKey, List<BigDecimal>> closeCache = new ConcurrentHashMap<>();
    // 최신 CNN 지수 단기 메모 — 같은 배치의 전략들이 공유, TTL 경과·실패 시 재조회 (spec 6.2)
    private final AtomicReference<FearGreedMemo> fearGreedMemo = new AtomicReference<>();
    InstantSource clock = InstantSource.system(); // TTL 판정용 시계 — 테스트에서 고정 시계로 교체
    // 직전 US 개장일 세션 메모 — 일봉 신선도 검증에서 같은 세션의 전략들이 캘린더 스캔을 공유
    private final Map<LocalDate, LocalDate> previousOpenCache = new ConcurrentHashMap<>();

    private record CandleKey(StrategyTicker ticker, LocalDate usSession) {}

    private record FearGreedMemo(BigDecimal value, Instant fetchedAt) {}

    // currentPrice: 배치 시작 시점 현재가(null 허용) — BUY 기준가 max(전일 종가, 현재가) 산정용
    // ledger: 원장(최신 cycle_position) — AVG_PRICE 평단·SELL 보유 상한·보유 비율 기준
    // existingOrders: 같은 사이클·거래일 PLANNED/PLACED 주문 — 규칙 leg가 있으면 재판정 없이 복구 대상
    DcaCollected collect(BatchContext ctx, LocalDate tradeDate, Set<OrderTiming> creatableTimings,
                         List<Order> existingOrders, BigDecimal currentPrice, AccountBalance ledger) {
        Strategy strategy = ctx.strategy();
        DcaStrategyConfig config = dcaDetailPort.findActiveByStrategyId(strategy.id())
                .orElseThrow(() -> new IllegalStateException("DCA 설정이 없습니다: " + strategy.id()));
        List<DcaCycleOrderStrategy.DcaRuleInputs> due = new ArrayList<>();
        Map<String, Order> recoveries = new LinkedHashMap<>();
        List<Order> todayOrders = null;          // 종결 주문 포함 당일 주문 — 처음 필요할 때 1회 조회
        // SELL 상한 = min(원장 보유 − 같은 사이클·거래일 기존 SELL − 취소된 접수 SELL, 증권사 판매가능수량 − 계좌 당일 예약 SELL) — 증권사 쪽은 SELL 규칙이 처음 필요할 때 1회
        BigDecimal sellable = ledger == null ? null : ledger.holdings().subtract(reservedSell(existingOrders)).max(BigDecimal.ZERO);
        BigDecimal buyCash = null;               // 주식 BUY 가용 USD — BUY 규칙이 처음 필요할 때 1회(실패 null)
        boolean buyCashLoaded = false;
        boolean sellCapped = false;              // 취소 SELL 차감·증권사 판매가능수량 상한 적용 여부 — SELL 규칙이 처음 필요할 때 1회
        for (DcaRule rule : config.rules()) {
            String key = rule.ruleKey();
            if (dcaExecutionPort.find(strategy.id(), key, tradeDate).isPresent()) continue; // 이미 판정됨

            // 주문 저장 후 이력 기록 전 중단된 재개 — 기존 규칙 주문으로 ORDERED 복구 (다른 timing 배치에서도 복구되도록 timing 판정보다 먼저)
            Optional<Order> existing = existingOrders.stream().filter(o -> rule.orderLeg().equals(o.orderLeg())).findFirst();
            if (existing.isPresent()) {
                recoveries.put(key, existing.get());
                continue;
            }

            if (!creatableTimings.contains(rule.timing())) continue;
            LocalDate usSession = marketCalendarPort.usSessionDateOf(tradeDate);
            if (!DcaSchedule.isExecutionDay(usSession, rule, marketCalendarPort::isUsSessionOpen)) continue;

            // PLANNED/PLACED 외 상태(FILLED 등)로 넘어간 당일 규칙 주문 — 이력만 누락된 경우 재주문하지 않고 복구
            if (todayOrders == null) todayOrders = orderPort.findByStrategyId(strategy.id(), tradeDate, tradeDate);
            Optional<Order> terminal = todayOrders.stream().filter(o -> rule.orderLeg().equals(o.orderLeg())).findFirst();
            if (terminal.isPresent()) {
                recoveries.put(key, terminal.get());
                continue;
            }

            boolean sell = rule.direction() == OrderDirection.SELL;
            // 접수 후 취소된 당일 SELL은 일부 체결됐을 수 있는데 원장은 마감 리포트 전까지 줄지 않는다 — 주문 수량 전체를 보수적으로 차감
            if (sell && sellable != null && !sellCapped) {
                sellable = sellable.subtract(cancelledPlacedSell(todayOrders)).max(BigDecimal.ZERO);
                if (UnassignedUsdCalculator.applies(ctx.account().brokerRef().broker())) {
                    Integer broker = loadBrokerSellable(ctx, tradeDate);
                    sellable = broker == null ? null : sellable.min(BigDecimal.valueOf(broker));
                }
                sellCapped = true;
            }
            if (!sell && !buyCashLoaded) {
                buyCash = loadBuyCash(ctx, existingOrders);
                buyCashLoaded = true;
            }
            due.add(new DcaCycleOrderStrategy.DcaRuleInputs(rule, ctx.account().brokerRef().broker().getMarket(),
                    loadCloses(strategy.ticker(), usSession), ledger, sell ? sellable : null,
                    loadFearGreed(rule), currentPrice, sell ? null : buyCash, null));
        }
        return new DcaCollected(new CycleOrderStrategy.PlanContext.DcaPlanInputs(List.copyOf(due)), Map.copyOf(recoveries));
    }

    // 증권사 판매가능수량 − 같은 계좌·거래일·종목 기존 PLANNED/PLACED SELL 예약분(다른 전략 포함), 실패 시 null
    private Integer loadBrokerSellable(BatchContext ctx, LocalDate tradeDate) {
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

    // 주식 BUY 가용 USD = 계좌 미배정 USD − 이 전략의 아직 증권사가 차감하지 않은 당일 BUY(PLANNED·SUBMITTING; PLACED는 이미 매수가능금액에 반영)
    // MOCK·US 주식 외 계좌는 상한 없음(UNLIMITED_CASH), 조회 실패는 null(DATA_UNAVAILABLE)
    // ponytail: 같은 계좌의 다른 DCA 전략 BUY는 차감하지 않는다 — 두 DCA가 미배정 USD를 각자 전부 보고 합계가 넘으면 증권사 거절(예수금 부족 알림). 계좌당 DCA 다수가 흔해지면 계좌 단위 차감으로 승격
    private BigDecimal loadBuyCash(BatchContext ctx, List<Order> existingOrders) {
        var ref = ctx.account().brokerRef();
        if (!UnassignedUsdCalculator.applies(ref.broker())) return DcaCycleOrderStrategy.UNLIMITED_CASH;
        try {
            BigDecimal free = unassignedUsd.available(ctx.account().id(), ref);
            if (free == null) return null;
            BigDecimal undeducted = existingOrders.stream()
                    .filter(o -> o.direction() == OrderDirection.BUY && o.price() != null
                            && (o.status() == OrderStatus.PLANNED || o.status() == OrderStatus.SUBMITTING))
                    .map(o -> o.quantity().multiply(o.price())).reduce(BigDecimal.ZERO, BigDecimal::add);
            return free.subtract(undeducted).max(BigDecimal.ZERO);
        } catch (RuntimeException e) {
            log.warn("[DCA] 주문 가능 USD 조회 실패: accountId={}", ctx.account().id(), e);
            return null;
        }
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
    private BigDecimal loadFearGreed(DcaRule rule) {
        if (!rule.hasIndicator(DcaIndicator.FEAR_GREED)) return null;
        FearGreedMemo memo = fearGreedMemo.get();
        Instant now = clock.instant();
        if (memo != null && memo.fetchedAt().plus(FEAR_GREED_TTL).isAfter(now)) return memo.value();
        BigDecimal value = fearGreedIndexPort.latest().orElse(null);
        if (value != null) fearGreedMemo.set(new FearGreedMemo(value, now));
        return value;
    }

    // 같은 사이클·거래일 기존 PLANNED/PLACED SELL 수량 합 — 커널이 같은 배치 앞 규칙 계획분을 따로 빼므로 이전 배치 분만
    // 증권사 접수(externalOrderId) 후 CANCELLED된 SELL 수량 합 — 취소 시점 체결량을 DB가 모르므로 상한으로 본다
    private static BigDecimal cancelledPlacedSell(List<Order> todayOrders) {
        return todayOrders.stream()
                .filter(o -> o.direction() == OrderDirection.SELL && o.status() == OrderStatus.CANCELLED && o.externalOrderId() != null)
                .map(Order::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal reservedSell(List<Order> existingOrders) {
        return existingOrders.stream().filter(o -> o.direction() == OrderDirection.SELL)
                .map(Order::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
