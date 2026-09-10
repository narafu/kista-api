package com.kista.trading.application.service;

import com.kista.sharedkernel.TimeZones;
import com.kista.account.domain.model.Account;
import com.kista.sharedkernel.Broker;
import com.kista.trading.domain.model.Order;
import com.kista.matching.domain.model.OrderTiming;
import com.kista.matching.domain.model.PlannedOrder;
import com.kista.broker.domain.model.PriceSnapshot;
import com.kista.privacy.domain.model.PrivacyTradeBase;
import com.kista.trading.domain.model.Strategy;
import com.kista.trading.domain.model.*;
import com.kista.matching.domain.model.*;
import com.kista.user.domain.model.User;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.privacy.application.port.output.PrivacyTradePort;
import com.kista.market.application.port.output.MarketCalendarPort;
import com.kista.trading.application.port.output.*;
import com.kista.matching.domain.strategy.CycleOrderStrategy;
import com.kista.matching.domain.strategy.CycleOrderStrategies;
import com.kista.trading.application.event.BatchInterruptedEvent;
import com.kista.trading.application.event.InsufficientBalanceEvent;
import com.kista.trading.application.event.MarketClosedEvent;
import com.kista.trading.application.event.TradingErrorEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
class TradingService {

    private final MarketCalendarPort marketCalendarPort;        // 미국 시장 개장일 확인 (DB 캐시)
    private final ApplicationEventPublisher eventPublisher;    // 관리자·사용자 알림 이벤트 발행 (오류·휴장·잔고부족)
    private final OrderPort orderPort;                         // 계획 주문 저장·조회
    private final StrategyCyclePort strategyCyclePort;         // 현재 StrategyCycle 조회
    private final TradingPriceFetcher priceFetcher;            // 가격 일괄 조회 + 단건 fallback
    private final TradingOrderExecutor orderExecutor;          // BUY 가격 보정 + 증권사 접수
    private final TradingReporter reporter;                    // 체결 조회 + 이력 저장 + 알림
    private final MarketEventNotifier marketEventNotifier;    // 장 이벤트 알림 (개장·마감 사용자 알림)
    private final TradingParallelRunner parallelRunner;        // 계좌별 동시 상한 내 사이클 병렬 실행
    private final TradingBatchGuard batchGuard;                 // 전략별 단계 실행 격리 가드
    private final TradingCandidatePlanner candidatePlanner;     // 후보수집 + 계좌별 예산 배정

    // 증권사 접수 결과: 사이클 상태 + 접수된 주문 목록
    private record CyclePlacedState(TradingCandidatePlanner.CycleState state, List<Order> mainOrders) {}

    void execute(Strategy strategy, Account account, User user) throws InterruptedException {
        // 현재 StrategyCycle 조회 — initialUsdDeposit 필요
        StrategyCycle currentCycle = strategyCyclePort.requireLatestByStrategyId(strategy.id());
        executeBatch(List.of(new BatchContext(strategy, currentCycle, account, user)));
    }

    void executeBatch(List<BatchContext> contexts) throws InterruptedException {
        executeBatch(contexts, DstInfo.calculate());
    }

    // package-private: DstInfo 주입으로 단위 테스트에서 sleep 우회
    void executeBatch(List<BatchContext> contexts, DstInfo dst) throws InterruptedException {
        // 아래 각 조기 반환은 정상 흐름(휴장·시작 전·계산 skip 등)이라 예외/오류 알림 대상이 아니지만,
        // "리포트가 안 왔는데 원인이 안 보이는" 재발 시 로그만으로 중단 지점을 특정하기 위해 사유를 남긴다.
        if (contexts.isEmpty()) {
            log.info("매매 배치 중단 — 대상 전략 0건");
            return;
        }

        LocalDate today = LocalDate.now(TimeZones.KST);

        // 시장 개장 여부 확인 (1회) — 모든 전략 공통, 가격 조회 전 조기 반환
        if (!isMarketOpen(today)) return;

        // 시작예정일 미도래 사이클 제외
        int contextsBeforeScheduledFilter = contexts.size();
        contexts = filterScheduledStart(contexts, today);
        if (contexts.isEmpty()) {
            log.info("매매 배치 중단 — 시작예정일 미도래로 대상 {}건 전량 제외", contextsBeforeScheduledFilter);
            return;
        }

        // 시작 시점 현재가 + 전일종가 + 기준 매매표(PRIVACY) 일괄 조회 (0회차 진입 방향 판단에 모두 필요)
        TradingPriceFetcher.PriceContext priceCtx = priceFetcher.loadPriceContext(contexts, today);

        // 슬롯별 후보 수집·예산 배정 — 누락된 AT_CLOSE 슬롯만 PLANNED로 저장
        List<TradingCandidatePlanner.CycleState> states = candidatePlanner.planAll(contexts, priceCtx.startPriceSnapshots(), priceCtx.privacyBase(), today);
        if (states.isEmpty()) {
            log.warn("매매 배치 중단 — 전략 계산 결과 0건 (대상 {}건 전량 skip, 원인은 위 'plan 후보 생성 오류'/'전략 계산 skip' 로그 참고)",
                    contexts.size());
            return;
        }

        // 공통 대기 — 주문 시각까지 (모든 전략이 공유하는 단 1회)
        // 이 시점 인터럽트 시 states(증권사 접수 전)는 전부 미처리 — 사용자 알림 대상
        try {
            waitFor("주문 시각", dst.waitUntilOrderTime(), dst);
        } catch (InterruptedException e) {
            batchGuard.notifyBatchInterrupted(states.stream().map(TradingCandidatePlanner.CycleState::ctx).toList());
            throw e;
        }

        // 증권사 접수 — 전략별: BUY 가격 보정 후 PLANNED → 증권사 접수
        List<CyclePlacedState> placedStates = placeAll(states, today);

        // 공통 대기 — 마감 시각까지 (모든 전략이 공유하는 단 1회)
        // 이 시점 인터럽트는 사용자 알림 대상 아님 — placedStates는 이미 증권사 접수 완료, 체결 리포트만 지연됨
        waitFor("마감 시각", dst.waitUntilPostClose(), dst);
        marketEventNotifier.notifyMarketClose();

        // 장 마감 후 확정 종가 일괄 조회 (라이브 현재가 아님 — KIS는 dailyprice, Toss/MOCK은 일봉 캔들 기반 확정 종가)
        Map<StrategyTicker, BigDecimal> closingPrices = priceFetcher.fetchClosingPrices(priceCtx.cycleTickers(), today, priceCtx.priceAccount());

        // recordAndNotifyExecutions — 전략별: 체결 조회 + 이력 저장 + 알림
        reportAll(placedStates, closingPrices, today);
    }

    // 전략별: BUY 가격 보정 후 PLANNED → 증권사 접수 (실패 사이클은 격리, 계좌별 동시 상한 내 병렬 실행)
    private List<CyclePlacedState> placeAll(List<TradingCandidatePlanner.CycleState> states, LocalDate today) throws InterruptedException {
        // 주문 시각 대기 직후 접수 대상 ticker의 현재가를 다시 일괄 조회한다.
        // states 수집 시점(startPrice)은 waitFor("주문 시각") 대기 시간만큼 stale할 수 있어
        // BUY cap 판단은 여기서 재조회한 최신가를 우선 사용한다 — ticker당 1회 조회(여러 전략 공유 무관)
        Map<StrategyTicker, BigDecimal> placementPrices = priceFetcher.reloadPlacementPrices(states);
        // groupKey=계좌 id — 같은 계좌 내 사이클끼리만 동시 상한 공유, 다른 계좌는 완전 병렬
        List<TradingParallelRunner.Task<CyclePlacedState>> tasks = states.stream()
                .map(state -> new TradingParallelRunner.Task<CyclePlacedState>(
                        state.ctx().account().id(),
                        () -> batchGuard.runSafely("증권사 접수", state.ctx(), () -> {
                            // 재조회 실패(해당 ticker 누락 또는 null)일 때만 시작가로 폴백
                            BigDecimal placementPrice = Optional.ofNullable(placementPrices.get(state.ctx().strategy().ticker()))
                                    .orElse(state.startPrice());
                            List<Order> mainOrders = orderExecutor.placeOrders(today,
                                    state.ctx().account(), state.ctx().currentCycle().id(),
                                    placementPrice, state.position(), state.vrPosition(), state.ctx().strategy());
                            // 선접수된 주문도 포함 — AT_OPEN(개장 스케쥴러) + AT_CLOSE(이전 세션/수동 접수) 모두
                            // 이미 placeOrders()로 접수된 주문과 중복 방지: ID 기준 dedup
                            Set<UUID> mainOrderIds = mainOrders.stream()
                                    .map(Order::id).collect(Collectors.toSet());
                            List<Order> prePlaced = orderPort
                                    .findPlacedByCycleAndDate(state.ctx().currentCycle().id(), today)
                                    .stream().filter(o -> !mainOrderIds.contains(o.id())).toList();
                            if (!prePlaced.isEmpty()) {
                                mainOrders = Stream.concat(prePlaced.stream(), mainOrders.stream()).toList();
                            }
                            return new CyclePlacedState(state, mainOrders);
                        })))
                .toList();
        return parallelRunner.runAll(tasks);
    }

    // 전략별: 체결 조회 + 이력 저장 + 알림 (실패 사이클은 격리, 계좌별 동시 상한 내 병렬 실행)
    private void reportAll(List<CyclePlacedState> placedStates, Map<StrategyTicker, BigDecimal> closingPrices, LocalDate today) throws InterruptedException {
        List<TradingParallelRunner.Task<Void>> tasks = placedStates.stream()
                .map(ps -> new TradingParallelRunner.Task<Void>(
                        ps.state().ctx().account().id(),
                        () -> batchGuard.runSafely("recordAndNotify", ps.state().ctx(), () -> {
                            reporter.recordAndNotify(today, ps.state().ctx(), ps.state().balance(),
                                    closingPrices.get(ps.state().ctx().strategy().ticker()),
                                    ps.mainOrders(), ps.state().privacyBase());
                            return null;
                        })))
                .toList();
        parallelRunner.runAll(tasks);
    }

    // package-private: DstInfo 주입으로 단위 테스트에서 sleep 우회 (단건 경로)
    void execute(Strategy strategy, Account account, User user, DstInfo dst) throws InterruptedException {
        StrategyCycle currentCycle = strategyCyclePort.requireLatestByStrategyId(strategy.id());
        executeBatch(List.of(new BatchContext(strategy, currentCycle, account, user)), dst);
    }

    void placeOpenOrders(List<BatchContext> contexts) throws InterruptedException {
        placeOpenOrders(contexts, DstInfo.calculate());
    }

    // package-private: DstInfo 주입으로 단위 테스트에서 sleep 우회
    void placeOpenOrders(List<BatchContext> contexts, DstInfo dst) throws InterruptedException {
        if (contexts.isEmpty()) return;

        LocalDate tradeDate = DstInfo.nextTradeDate(); // 장 개시 스케쥴러 전날 저녁 실행 — 내일이 KST 거래일
        log.info("개장 order 생성 + INFINITE 매도 선접수 시작 — 거래일 {}", tradeDate);

        if (!isMarketOpen(tradeDate)) return;

        // 시작예정일 미도래 사이클 제외
        contexts = filterScheduledStart(contexts, tradeDate);
        if (contexts.isEmpty()) return;

        // 가격 스냅샷 + PRIVACY 기준 매매표 일괄 조회 (개장 전 현시점, 내일 기준 — FIDA가 미리 송신했을 경우)
        TradingPriceFetcher.PriceContext priceCtx = priceFetcher.loadPriceContext(contexts, tradeDate);

        // 개장 시각까지 대기 — 이 시점 인터럽트 시 contexts 전부가 미처리 — 사용자 알림 대상
        try {
            waitFor("개장 시각", dst.waitUntilMarketOpen(), dst);
        } catch (InterruptedException e) {
            batchGuard.notifyBatchInterrupted(contexts);
            throw e;
        }
        marketEventNotifier.notifyMarketOpen();

        // 후보를 모두 수집한 뒤 계좌별 BUY 예산을 배정하고 AT_OPEN 주문만 선접수
        // AT_CLOSE는 여기서 생성하지 않는다 — 캡(BuyOrderPriceCapper)이 개장 시점 가격으로 고정돼 마감 접수 시점까지
        // 재평가되지 않는 stale-cap 문제를 막기 위해, AT_CLOSE 계산·캡·예산배정·접수는 close 스케쥴러가 전담한다
        List<TradingCandidatePlanner.CyclePlanCandidate> candidates =
                candidatePlanner.collectOpenCandidates(contexts, priceCtx.startPriceSnapshots(), priceCtx.privacyBase(), tradeDate);

        TradingCandidatePlanner.SaveAllocationResult result = candidatePlanner.saveAllocatedOrders(candidates, tradeDate);
        // position/vrPosition/시작가까지 담긴 CycleState 그대로 접수 단계로 전달 — BatchContext만 넘기면
        // VR_POSITION 등 BUY cap 보정에 필요한 정보가 유실된다 (planAll()과 동일 패턴)
        List<TradingCandidatePlanner.CycleState> placeableStates = candidates.stream()
                .filter(candidate -> candidate.hasExistingOrders()
                        || result.savedContexts().contains(candidate.state().ctx()))
                .map(TradingCandidatePlanner.CyclePlanCandidate::state)
                .toList();

        if (!placeableStates.isEmpty()) {
            // 개장 시각 대기(waitUntilMarketOpen) 이후 접수 대상 ticker의 현재가를 다시 일괄 조회한다.
            // AT_CLOSE 접수(placeAll)의 reloadPlacementPrices와 동일한 staleness 우려 — ticker당 1회 조회
            Map<StrategyTicker, BigDecimal> placementPrices = priceFetcher.reloadPlacementPrices(placeableStates);
            for (TradingCandidatePlanner.CycleState state : placeableStates) {
                batchGuard.runSafely("개장 AT_OPEN 접수", state.ctx(), () -> {
                    BigDecimal placementPrice = Optional.ofNullable(placementPrices.get(state.ctx().strategy().ticker()))
                            .orElse(state.startPrice());
                    placeAtOpenPlannedOrders(state, placementPrice, tradeDate);
                    return null;
                });
            }
        }

        log.info("개장 order 생성 + INFINITE 매도 선접수 완료");
    }

    // 개장 시점에는 AT_OPEN 슬롯의 PLANNED 주문만 즉시 증권사에 접수한다 — BUY cap 보정은 orderExecutor가 AT_OPEN 스코프로 적용
    private void placeAtOpenPlannedOrders(TradingCandidatePlanner.CycleState state, BigDecimal placementPrice, LocalDate tradeDate) {
        orderExecutor.placeAtOpenOrders(tradeDate, state.ctx().account(), state.ctx().currentCycle().id(),
                placementPrice, state.position(), state.vrPosition(), state.ctx().strategy());
    }

    // 지정 시각까지 대기 — DST 정보 로깅 후 sleep, 도달 로그
    private void waitFor(String label, Duration duration, DstInfo dst) throws InterruptedException {
        long ms = duration.toMillis();
        log.info("DST={}, {}까지 대기: {}ms", dst.isDst(), label, ms);
        try {
            if (ms > 0) Thread.sleep(ms);
        } catch (InterruptedException e) {
            // 배포·재시작으로 인한 강제 종료 — PLANNED 주문이 접수되지 않을 수 있음
            eventPublisher.publishEvent(new TradingErrorEvent(null,
                    "[스케쥴러 인터럽트] " + label + " 대기 중 강제 종료 — PLANNED 주문 접수 미실행 가능"));
            throw e;
        }
        log.info("{} 도달", label);
    }

    // false 반환 시 알림 발송 후 executeBatch에서 조기 반환
    private boolean isMarketOpen(LocalDate today) {
        boolean open = marketCalendarPort.isMarketOpen(today);
        log.info("시장 개장 여부: {}", open);
        if (!open) {
            log.info("휴장일 — 매매 건너뜀");
            eventPublisher.publishEvent(new MarketClosedEvent());
        }
        return open;
    }

    // 시작예정일 미도래 사이클 제외 — tradeDate가 startDate 이후일 때만 집행 (tradeDate > startDate)
    private List<BatchContext> filterScheduledStart(List<BatchContext> contexts, LocalDate tradeDate) {
        return contexts.stream().filter(ctx -> {
            boolean started = tradeDate.isAfter(ctx.currentCycle().startDate());
            if (!started) log.info("[strategyId={}] 시작예정일 미도래 skip (startDate={}, tradeDate={})",
                    ctx.strategy().id(), ctx.currentCycle().startDate(), tradeDate);
            return started;
        }).toList();
    }

}
