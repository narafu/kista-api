package com.kista.application.service.trading;

import com.kista.common.CycleLookups;
import com.kista.common.TimeZones;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.*;
import com.kista.domain.model.user.User;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.*;
import com.kista.domain.strategy.CycleOrderStrategy;
import com.kista.domain.strategy.CycleOrderStrategies;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final NotifyPort notifyPort;                       // 관리자 텔레그램 알림 (오류·휴장·잔고부족)
    private final UserNotificationPort userNotificationPort;   // 사용자 알림 (예수금 부족 등)
    private final OrderPort orderPort;                         // 계획 주문 저장·조회
    private final PrivacyTradePort privacyTradePort;
    private final StrategyCyclePort strategyCyclePort;         // 현재 StrategyCycle 조회
    private final TradingBalanceLoader balanceLoader;          // 잔고 로드 헬퍼 — KIS·Toss 모두 DB 이력(cycle_position)
    private final CycleOrderComputer orderComputer;            // 전략 계산 + 주문 유효성 검증 공통부
    private final TradingOrderPlanner orderPlanner;            // PLANNED 주문 저장 헬퍼
    private final TradingPriceFetcher priceFetcher;            // 가격 일괄 조회 + 단건 fallback
    private final TradingOrderExecutor orderExecutor;          // BUY 가격 보정 + 증권사 접수
    private final TradingReporter reporter;                    // 체결 조회 + 이력 저장 + 알림
    private final MarketEventNotifier marketEventNotifier;    // 장 이벤트 알림 (개장·마감 사용자 알림)
    private final TradingOrderBudgetAllocator budgetAllocator; // 계좌별 BUY 예산·SELL 판매가능수량 배정
    private final BuyOrderPriceCapper priceCapper;             // 신규 후보 BUY 가격 cap 계산 (영속화 없음)
    private final CycleOrderStrategies cycleOrderStrategies;   // 전략별 BUY 가격 cap 방식 조회

    // 슬롯별 후보 수집 결과: 전략별 잔고·전략 계산 상태
    private record CycleState(
            BatchContext ctx,
            AccountBalance balance,
            InfinitePosition position,      // INFINITE만 non-null (신규 계산 시 — pre-existing skip 케이스는 null)
            BigDecimal startPrice,          // 공통 — INFINITE: BuyOrderPriceCapper / PRIVACY: capPrivacyIfNeeded
            PrivacyTradeBase privacyBase    // PRIVACY만 non-null (rotation 시 최소금액 산정용)
    ) {}

    // 증권사 접수 결과: 사이클 상태 + 접수된 주문 목록
    private record CyclePlacedState(CycleState state, List<Order> mainOrders) {}

    // 전략 계산 결과 중 신규 생성 가능한 주문만 allocator에 전달하기 위한 후보
    private record CyclePlanCandidate(
            CycleState state,
            List<Order> creatableOrders,
            boolean hasExistingOrders
    ) {}

    // 예산 배정 후 실제 저장된 컨텍스트만 다음 단계 진입 대상으로 사용한다
    private record SaveAllocationResult(Set<BatchContext> savedContexts) {}

    // 배치 시작 시점 가격·기준표 조회 결과 — executeBatch/placeOpenOrders 공통 (조회 대상 날짜만 다름)
    private record PriceContext(
            List<Ticker> cycleTickers,
            Account priceAccount,
            Map<Ticker, PriceSnapshot> startPriceSnapshots,
            PrivacyTradeBase privacyBase
    ) {}

    void execute(Strategy strategy, Account account, User user) throws InterruptedException {
        // 현재 StrategyCycle 조회 — initialUsdDeposit 필요
        StrategyCycle currentCycle = CycleLookups.requireLatestCycle(strategyCyclePort, strategy.id());
        executeBatch(List.of(new BatchContext(strategy, currentCycle, account, user)));
    }

    void executeBatch(List<BatchContext> contexts) throws InterruptedException {
        executeBatch(contexts, DstInfo.calculate());
    }

    // package-private: DstInfo 주입으로 단위 테스트에서 sleep 우회
    void executeBatch(List<BatchContext> contexts, DstInfo dst) throws InterruptedException {
        if (contexts.isEmpty()) return;

        LocalDate today = LocalDate.now(TimeZones.KST);

        // 시장 개장 여부 확인 (1회) — 모든 전략 공통, 가격 조회 전 조기 반환
        if (!isMarketOpen(today)) return;

        // 시작 시점 현재가 + 전일종가 + 기준 매매표(PRIVACY) 일괄 조회 (0회차 진입 방향 판단에 모두 필요)
        PriceContext priceCtx = loadPriceContext(contexts, today);

        // 슬롯별 후보 수집·예산 배정 — 누락된 AT_CLOSE 슬롯만 PLANNED로 저장
        List<CycleState> states = planAll(contexts, priceCtx.startPriceSnapshots(), priceCtx.privacyBase(), today);
        if (states.isEmpty()) return;

        // 공통 대기 — 주문 시각까지 (모든 전략이 공유하는 단 1회)
        // 이 시점 인터럽트 시 states(증권사 접수 전)는 전부 미처리 — 사용자 알림 대상
        try {
            waitFor("주문 시각", dst.waitUntilOrderTime(), dst);
        } catch (InterruptedException e) {
            notifyBatchInterrupted(states.stream().map(CycleState::ctx).toList());
            throw e;
        }

        // 증권사 접수 — 전략별: BUY 가격 보정 후 PLANNED → 증권사 접수
        List<CyclePlacedState> placedStates = placeAll(states, today);

        // 공통 대기 — 마감 시각까지 (모든 전략이 공유하는 단 1회)
        // 이 시점 인터럽트는 사용자 알림 대상 아님 — placedStates는 이미 증권사 접수 완료, 체결 리포트만 지연됨
        waitFor("마감 시각", dst.waitUntilPostClose(), dst);
        marketEventNotifier.notifyMarketClose();

        // 장 마감 후 확정 종가 일괄 조회 (라이브 현재가 아님 — KIS는 dailyprice, Toss는 라이브 위임)
        Map<Ticker, BigDecimal> closingPrices = priceFetcher.fetchClosingPrices(priceCtx.cycleTickers(), today, priceCtx.priceAccount());

        // recordAndNotifyExecutions — 전략별: 체결 조회 + 이력 저장 + 알림
        reportAll(placedStates, closingPrices, today);
    }

    // 전략별 후보를 먼저 수집하고 계좌별 예산 배정 후 AT_CLOSE 주문만 저장
    private List<CycleState> planAll(List<BatchContext> contexts, Map<Ticker, PriceSnapshot> startPriceSnapshots,
                                      PrivacyTradeBase privacyBase, LocalDate today) throws InterruptedException {
        List<CyclePlanCandidate> candidates = new ArrayList<>();
        for (BatchContext ctx : contexts) {
            runSafely("plan 후보 생성", ctx,
                    () -> collectCycleCandidate(ctx, startPriceSnapshots, privacyBase, today,
                            EnumSet.of(Order.OrderTiming.AT_CLOSE)))
                    .ifPresent(candidates::add);
        }
        SaveAllocationResult result = saveAllocatedOrders(candidates, today);
        return candidates.stream()
                .filter(candidate -> candidate.hasExistingOrders()
                        || result.savedContexts().contains(candidate.state().ctx()))
                .map(CyclePlanCandidate::state)
                .toList();
    }

    // 전략별: BUY 가격 보정 후 PLANNED → 증권사 접수 (실패 사이클은 격리)
    private List<CyclePlacedState> placeAll(List<CycleState> states, LocalDate today) throws InterruptedException {
        List<CyclePlacedState> placedStates = new ArrayList<>();
        for (CycleState state : states) {
            runSafely("증권사 접수", state.ctx(), () -> {
                List<Order> mainOrders = orderExecutor.placeOrders(today,
                        state.ctx().account(), state.ctx().currentCycle().id(),
                        state.startPrice(), state.position(), state.ctx().strategy());
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
            }).ifPresent(placedStates::add);
        }
        return placedStates;
    }

    // 전략별: 체결 조회 + 이력 저장 + 알림 (실패 사이클은 격리)
    private void reportAll(List<CyclePlacedState> placedStates, Map<Ticker, BigDecimal> closingPrices, LocalDate today) throws InterruptedException {
        for (CyclePlacedState ps : placedStates) {
            CycleState st = ps.state();
            runSafely("recordAndNotify", st.ctx(), () -> {
                reporter.recordAndNotify(today, st.ctx(), st.balance(),
                        closingPrices.get(st.ctx().strategy().ticker()),
                        ps.mainOrders(), st.privacyBase());
                return null;
            });
        }
    }

    // 잔고 로드 — KIS·Toss 모두 cycle_position DB 이력 사용 (전략 공식 기준)
    private AccountBalance loadBalance(Strategy strategy, Account account) {
        AccountBalance balance = balanceLoader.loadBalanceOrThrow(strategy).balance();
        log.info("잔고 조회: [{}] {} {}주, 통합주문가능금액 ${}",
                account.nickname(), strategy.ticker().name(), balance.holdings(), balance.usdDeposit());
        return balance;
    }

    // 인터럽트 시점에 아직 증권사 접수가 안 된 전략들에게 알림 (증권사 접수 완료된 전략은 대상 아님)
    private void notifyBatchInterrupted(List<BatchContext> contexts) {
        contexts.forEach(ctx -> {
            try {
                userNotificationPort.notifyBatchInterrupted(ctx.user(), ctx.account());
            } catch (Exception notifyEx) {
                log.warn("[strategyId={}] 인터럽트 알림 발송 실패: {}", ctx.strategy().id(), notifyEx.getMessage());
            }
        });
    }

    // creatableTimings 필터 후 TradingOrderSlots로 기존 주문과 동일 슬롯을 제외한다 (TradingPreviewService와 공유 기준)
    private List<Order> filterCreatableOrders(List<Order> plannedTemplates, List<Order> existingOrders,
                                              Set<Order.OrderTiming> creatableTimings) {
        List<Order> timingFiltered = plannedTemplates.stream()
                .filter(order -> creatableTimings.contains(order.timing()))
                .toList();
        return TradingOrderSlots.excludeExisting(timingFiltered, existingOrders);
    }

    // 사이클별 후보 수집 — 기존 주문은 보존하고 새 슬롯만 allocator 검증 대상으로 분리한다
    private CyclePlanCandidate collectCycleCandidate(BatchContext ctx,
            Map<Ticker, PriceSnapshot> startPriceSnapshots, PrivacyTradeBase privacyBase,
            LocalDate tradeDate, Set<Order.OrderTiming> creatableTimings) {
        Strategy strategy = ctx.strategy();
        Account account = ctx.account();
        AccountBalance balance = loadBalance(strategy, account);
        PriceSnapshot priceSnapshot = startPriceSnapshots.get(strategy.ticker());
        BigDecimal price = priceSnapshot != null ? priceSnapshot.current() : null;
        BigDecimal prevClosePrice = PriceSnapshot.prevCloseOrNull(priceSnapshot);
        List<Order> existingOrders = orderPort.findPlannedOrPlacedByCycleAndDate(ctx.currentCycle().id(), tradeDate);
        CycleOrderStrategy strategyHandler = cycleOrderStrategies.of(strategy.type());
        if (!existingOrders.isEmpty()
                && strategyHandler.canSkipOrderComputation(existingOrders, creatableTimings)) {
            CycleState existingState = buildCycleStateFromExistingOrders(
                    ctx, balance, priceSnapshot, privacyBase, tradeDate, existingOrders.size(), false);
            return new CyclePlanCandidate(existingState, List.of(), true);
        }
        Optional<CycleOrderStrategy.OrderPlan> planOpt = orderComputer.compute(
                balance, strategy, prevClosePrice, tradeDate, ctx.currentCycle(), privacyBase, account.nickname(), price);
        if (planOpt.isEmpty()) {
            log.info("[{}] 전략 계산 skip (PRIVACY 기준 미수신 등)", account.nickname());
            if (existingOrders.isEmpty()) return null;
            CycleState existingState = buildCycleStateFromExistingOrders(
                    ctx, balance, priceSnapshot, privacyBase, tradeDate, existingOrders.size(), true);
            return new CyclePlanCandidate(existingState, List.of(), true);
        }

        // 예산 배정 전에 전략별 가격 cap을 반영해 최종 BUY 수량과 correction 주문까지 포함한다.
        List<Order> preparedOrders = priceCapper.prepareForAllocation(
                planOpt.get().orders(), price, planOpt.get().position(),
                cycleOrderStrategies.of(strategy.type()).priceCapMode(), tradeDate);
        validateConcreteOrderLegs(strategy, preparedOrders);
        List<Order> creatableOrders = filterCreatableOrders(
                preparedOrders, existingOrders, creatableTimings);
        PrivacyTradeBase privacyBaseForState = strategy.isPrivacy() ? privacyBase : null;
        CycleState state = new CycleState(ctx, balance, planOpt.get().position(), price, privacyBaseForState);
        return new CyclePlanCandidate(state, creatableOrders, !existingOrders.isEmpty());
    }

    private void validateConcreteOrderLegs(Strategy strategy, List<Order> orders) {
        List<Order> unknownLegOrders = orders.stream()
                .filter(order -> Order.UNKNOWN_LEG.equals(order.orderLeg()))
                .toList();
        if (!unknownLegOrders.isEmpty()) {
            throw new IllegalStateException("전략 주문 leg 누락: strategyType="
                    + strategy.type() + ", count=" + unknownLegOrders.size());
        }
    }

    // 오늘 PLANNED·PLACED 주문이 이미 있을 때 캡 보정을 위해 position만 재계산 (저장 없음)
    // INFINITE: 필요할 때만 position 재계산, PRIVACY: privacyBase만 담아 반환
    private CycleState buildCycleStateFromExistingOrders(BatchContext ctx, AccountBalance balance,
            PriceSnapshot priceSnapshot, PrivacyTradeBase privacyBase, LocalDate today, int existingCount,
            boolean recalculateInfinitePosition) {
        Strategy strategy = ctx.strategy();
        Account account = ctx.account();
        BigDecimal price = priceSnapshot != null ? priceSnapshot.current() : null;
        log.info("[{}] 오늘 주문 {}건 존재 — 재계산 skip", account.nickname(), existingCount);
        if (strategy.isInfinite() && recalculateInfinitePosition) {
            // position 재계산: 저장 없이 매수 보정(BuyOrderPriceCapper)용으로만 사용
            BigDecimal prevClosePrice = PriceSnapshot.prevCloseOrNull(priceSnapshot);
            InfinitePosition recalcPos = orderComputer.compute(
                    balance, strategy, prevClosePrice, today, ctx.currentCycle(), null, account.nickname(), price)
                    .map(CycleOrderStrategy.OrderPlan::position).orElse(null);
            return new CycleState(ctx, balance, recalcPos, price, null);
        }
        // PRIVACY: price 전달 — capPrivacyIfNeeded에서 현재가 기반 BUY 가격 캡 적용
        // VR: privacyBase 오염 방지 (혼합 배치 시 hasPrivacy=true로 조회됐을 수 있음)
        PrivacyTradeBase privacyBaseForState = strategy.isPrivacy() ? privacyBase : null;
        return new CycleState(ctx, balance, null, price, privacyBaseForState);
    }

    // package-private: DstInfo 주입으로 단위 테스트에서 sleep 우회 (단건 경로)
    void execute(Strategy strategy, Account account, User user, DstInfo dst) throws InterruptedException {
        StrategyCycle currentCycle = CycleLookups.requireLatestCycle(strategyCyclePort, strategy.id());
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

        // 가격 스냅샷 + PRIVACY 기준 매매표 일괄 조회 (개장 전 현시점, 내일 기준 — FIDA가 미리 송신했을 경우)
        PriceContext priceCtx = loadPriceContext(contexts, tradeDate);

        // 개장 시각까지 대기 — 이 시점 인터럽트 시 contexts 전부가 미처리 — 사용자 알림 대상
        try {
            waitFor("개장 시각", dst.waitUntilMarketOpen(), dst);
        } catch (InterruptedException e) {
            notifyBatchInterrupted(contexts);
            throw e;
        }
        marketEventNotifier.notifyMarketOpen();

        // 후보를 모두 수집한 뒤 계좌별 BUY 예산을 배정하고 AT_OPEN 주문만 선접수
        List<CyclePlanCandidate> candidates = new ArrayList<>();
        for (BatchContext ctx : contexts) {
            runSafely("개장 order 후보 생성", ctx,
                    () -> collectCycleCandidate(ctx, priceCtx.startPriceSnapshots(), priceCtx.privacyBase(),
                            tradeDate, EnumSet.allOf(Order.OrderTiming.class)))
                    .ifPresent(candidates::add);
        }

        SaveAllocationResult result = saveAllocatedOrders(candidates, tradeDate);
        Set<BatchContext> placeableContexts = candidates.stream()
                .filter(candidate -> candidate.hasExistingOrders()
                        || result.savedContexts().contains(candidate.state().ctx()))
                .map(candidate -> candidate.state().ctx())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (BatchContext ctx : placeableContexts) {
            runSafely("개장 AT_OPEN 접수", ctx, () -> {
                placeAtOpenPlannedOrders(ctx.account(), ctx.currentCycle().id(), tradeDate);
                return null;
            });
        }

        log.info("개장 order 생성 + INFINITE 매도 선접수 완료");
    }

    // allocator 승인 주문만 PLANNED 저장하고 BUY/SELL 거절 사이클에는 기존 잔고 부족 알림을 재사용한다
    private SaveAllocationResult saveAllocatedOrders(List<CyclePlanCandidate> candidates, LocalDate tradeDate)
            throws InterruptedException {
        Map<UUID, List<TradingOrderBudgetAllocator.Candidate>> candidatesByAccount = new LinkedHashMap<>();
        candidates.stream()
                .filter(candidate -> !candidate.creatableOrders().isEmpty())
                .map(candidate -> new TradingOrderBudgetAllocator.Candidate(
                        candidate.state().ctx(), candidate.creatableOrders()))
                .forEach(candidate -> candidatesByAccount
                        .computeIfAbsent(candidate.ctx().account().id(), ignored -> new ArrayList<>())
                        .add(candidate));

        Set<BatchContext> savedContexts = new LinkedHashSet<>();
        List<TradingOrderBudgetAllocator.Allocation> allocations = new ArrayList<>();

        // 계좌별 예산 조회 실패가 다른 계좌의 주문 생성을 막지 않도록 격리한다.
        for (List<TradingOrderBudgetAllocator.Candidate> accountCandidates : candidatesByAccount.values()) {
            BatchContext firstContext = accountCandidates.getFirst().ctx();
            Optional<TradingOrderBudgetAllocator.Allocation> allocation = runSafely("계좌 주문 예산 배정", firstContext,
                    () -> budgetAllocator.allocate(accountCandidates, tradeDate));
            if (allocation.isPresent()) {
                allocations.add(allocation.get());
            }
        }

        for (TradingOrderBudgetAllocator.Allocation allocation : allocations) {
            for (TradingOrderBudgetAllocator.Candidate approved : allocation.approved()) {
                Optional<BatchContext> saved = runSafely("계획 주문 저장", approved.ctx(), () -> {
                    orderPlanner.savePlannedOrders(
                            approved.orders(), approved.ctx().account(), approved.ctx().currentCycle().id());
                    return approved.ctx();
                });
                if (saved.isPresent()) {
                    savedContexts.add(saved.get());
                }
            }

            Set<BatchContext> rejectedContexts = Stream.concat(
                            allocation.rejectedBuy().stream(), allocation.rejectedSell().stream())
                    .map(TradingOrderBudgetAllocator.Candidate::ctx)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            for (BatchContext ctx : rejectedContexts) {
                runSafely("예수금 부족 알림", ctx, () -> {
                        userNotificationPort.notifyInsufficientBalance(
                                ctx.user(), ctx.account(), ctx.strategy().type(), ctx.strategy().ticker());
                        return null;
                    });
            }
        }

        return new SaveAllocationResult(Set.copyOf(savedContexts));
    }

    // 개장 시점에는 AT_OPEN 슬롯의 PLANNED 주문만 즉시 증권사에 접수한다
    private void placeAtOpenPlannedOrders(Account account, UUID cycleId, LocalDate tradeDate) {
        List<Order> atOpenOrders = orderPort.findAtOpenPlannedByCycleAndDate(cycleId, tradeDate);
        if (atOpenOrders.isEmpty()) {
            log.info("[{}] 개장 선접수할 주문 없음", account.nickname());
            return;
        }
        orderExecutor.placeGiven(atOpenOrders, account);
    }

    // 지정 시각까지 대기 — DST 정보 로깅 후 sleep, 도달 로그
    private void waitFor(String label, Duration duration, DstInfo dst) throws InterruptedException {
        long ms = duration.toMillis();
        log.info("DST={}, {}까지 대기: {}ms", dst.isDst(), label, ms);
        try {
            if (ms > 0) Thread.sleep(ms);
        } catch (InterruptedException e) {
            // 배포·재시작으로 인한 강제 종료 — PLANNED 주문이 접수되지 않을 수 있음
            notifyPort.notifyError(new IllegalStateException(
                    "[스케쥴러 인터럽트] " + label + " 대기 중 강제 종료 — PLANNED 주문 접수 미실행 가능", e));
            throw e;
        }
        log.info("{} 도달", label);
    }

    // 가격 조회에 사용할 계좌 선택 — Toss 계좌가 있으면 우선 사용 (토스 시세 API 일관성)
    private Account selectPriceAccount(List<BatchContext> contexts) {
        return contexts.stream()
                .map(BatchContext::account)
                .filter(a -> a.broker() == Account.Broker.TOSS)
                .findFirst()
                .orElseGet(() -> contexts.getFirst().account());
    }

    // 배치 시작 시점 현재가 + 전일종가 + 기준 매매표(PRIVACY) 일괄 조회 — executeBatch/placeOpenOrders 공통
    // date: executeBatch는 today(당일), placeOpenOrders는 tradeDate(익일 US 거래일)
    private PriceContext loadPriceContext(List<BatchContext> contexts, LocalDate date) {
        List<Ticker> cycleTickers = contexts.stream()
                .map(c -> c.strategy().ticker())
                .distinct().toList();
        Account priceAccount = selectPriceAccount(contexts); // Toss 계좌 우선
        Map<Ticker, PriceSnapshot> startPriceSnapshots = priceFetcher.fetchPriceSnapshots(cycleTickers, priceAccount);

        // 기준 매매표 조회 (PRIVACY)
        boolean hasPrivacy = contexts.stream().anyMatch(c -> c.strategy().isPrivacy());
        PrivacyTradeBase privacyBase = hasPrivacy
                ? privacyTradePort.findTodayTrade(date).orElse(null)
                : null;

        return new PriceContext(cycleTickers, priceAccount, startPriceSnapshots, privacyBase);
    }

    // false 반환 시 알림 발송 후 executeBatch에서 조기 반환
    private boolean isMarketOpen(LocalDate today) {
        boolean open = marketCalendarPort.isMarketOpen(today);
        log.info("시장 개장 여부: {}", open);
        if (!open) {
            log.info("휴장일 — 매매 건너뜀");
            notifyPort.notifyMarketClosed();
        }
        return open;
    }

    // 전략별 단계 실행 — 예외 발생 시 로그 + 관리자 + 사용자 알림 후 Optional.empty() 반환 (격리 실행)
    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private <T> Optional<T> runSafely(String phase, BatchContext ctx, ThrowingSupplier<T> supplier) throws InterruptedException {
        try {
            return Optional.ofNullable(supplier.get());
        } catch (InterruptedException e) {
            throw e; // InterruptedException은 삼키지 않음
        } catch (Exception e) {
            log.error("[strategyId={}] {} 오류: {}", ctx.strategy().id(), phase, e.getMessage(), e);
            notifyErrorSafely(ctx, e);
            return Optional.empty();
        }
    }

    private void notifyErrorSafely(BatchContext ctx, Exception e) {
        try {
            notifyPort.notifyError(e);
        } catch (Exception notifyEx) {
            log.warn("[strategyId={}] 관리자 오류 알림 실패: {}", ctx.strategy().id(), notifyEx.getMessage());
        }
        try {
            userNotificationPort.notifyError(ctx.user(), e);
        } catch (Exception notifyEx) {
            log.warn("[strategyId={}] 사용자 오류 알림 실패: {}", ctx.strategy().id(), notifyEx.getMessage());
        }
    }
}
