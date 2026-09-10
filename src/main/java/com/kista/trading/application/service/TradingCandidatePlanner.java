package com.kista.trading.application.service;

import com.kista.account.domain.model.Account;
import com.kista.broker.domain.model.PriceSnapshot;
import com.kista.matching.domain.model.*;
import com.kista.matching.domain.strategy.CycleOrderStrategies;
import com.kista.matching.domain.strategy.CycleOrderStrategy;
import com.kista.privacy.domain.model.PrivacyTradeBase;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.trading.application.event.InsufficientBalanceEvent;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.trading.domain.model.BatchContext;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.Strategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// 전략별 후보 수집(잔고 로드 + 전략 계산 + 가격 캡) + 계좌별 예산 배정 — executeBatch(AT_CLOSE)/placeOpenOrders(AT_OPEN) 공용
@Component
@RequiredArgsConstructor
@Slf4j
class TradingCandidatePlanner {

    private final OrderPort orderPort;
    private final CycleOrderComputer orderComputer;
    private final TradingOrderPlanner orderPlanner;
    private final BuyOrderPriceCapper priceCapper;
    private final CycleOrderStrategies cycleOrderStrategies;
    private final TradingOrderBudgetAllocator budgetAllocator;
    private final TradingBalanceLoader balanceLoader;
    private final ApplicationEventPublisher eventPublisher; // 예수금 부족 알림(InsufficientBalanceEvent)
    private final TradingBatchGuard batchGuard;

    // 슬롯별 후보 수집 결과: 전략별 잔고·전략 계산 상태
    record CycleState(
            BatchContext ctx,
            AccountBalance balance,
            InfinitePosition position,      // INFINITE만 non-null (신규 계산 시 — pre-existing skip 케이스는 null)
            VrPosition vrPosition,          // VR만 non-null (신규 계산 시 — BuyOrderPriceCapper VR_POSITION 보정용)
            BigDecimal startPrice,          // 배치 시작 시점 가격 — placeAll()에서 접수 직전 재조회(reloadPlacementPrices) 실패 시 폴백으로만 사용
            PrivacyTradeBase privacyBase    // PRIVACY만 non-null (rotation 시 최소금액 산정용)
    ) {}

    // 전략 계산 결과 중 신규 생성 가능한 주문만 allocator에 전달하기 위한 후보
    record CyclePlanCandidate(
            CycleState state,
            List<PlannedOrder> creatableOrders,
            boolean hasExistingOrders
    ) {}

    // 예산 배정 후 실제 저장된 컨텍스트만 다음 단계 진입 대상으로 사용한다
    record SaveAllocationResult(Set<BatchContext> savedContexts) {}

    // 전략별 후보를 먼저 수집하고 계좌별 예산 배정 후 AT_CLOSE 주문만 저장
    List<CycleState> planAll(List<BatchContext> contexts, Map<StrategyTicker, PriceSnapshot> startPriceSnapshots,
                              PrivacyTradeBase privacyBase, LocalDate today) throws InterruptedException {
        List<CyclePlanCandidate> candidates = new ArrayList<>();
        for (BatchContext ctx : contexts) {
            batchGuard.runSafely("plan 후보 생성", ctx,
                    () -> collectCycleCandidate(ctx, startPriceSnapshots, privacyBase, today,
                            EnumSet.of(OrderTiming.AT_CLOSE)))
                    .ifPresent(candidates::add);
        }
        SaveAllocationResult result = saveAllocatedOrders(candidates, today);
        return candidates.stream()
                .filter(candidate -> candidate.hasExistingOrders()
                        || result.savedContexts().contains(candidate.state().ctx())
                        // 롤오버 판정이 필요한 전략(VR)은 당일 주문 0건이어도 마감 리포트까지 흘려보내야
                        // saveCyclePosition→rollIfDue가 매일 실행된다 (예수금 0으로 사다리를 못 만드는 날에도 롤오버는 계속 판정돼야 함)
                        || cycleOrderStrategies.of(candidate.state().ctx().strategy().type()).requiresRolloverCheck())
                .map(CyclePlanCandidate::state)
                .toList();
    }

    // 개장 후보 수집 — placeOpenOrders 전용, planAll과 달리 저장까지 이 메서드 밖(TradingService)에서 처리
    List<CyclePlanCandidate> collectOpenCandidates(List<BatchContext> contexts,
            Map<StrategyTicker, PriceSnapshot> startPriceSnapshots, PrivacyTradeBase privacyBase,
            LocalDate tradeDate) throws InterruptedException {
        List<CyclePlanCandidate> candidates = new ArrayList<>();
        for (BatchContext ctx : contexts) {
            batchGuard.runSafely("개장 order 후보 생성", ctx,
                    () -> collectCycleCandidate(ctx, startPriceSnapshots, privacyBase, tradeDate,
                            EnumSet.of(OrderTiming.AT_OPEN)))
                    .ifPresent(candidates::add);
        }
        return candidates;
    }

    // 잔고 로드 — KIS·Toss 모두 cycle_position DB 이력 사용 (전략 공식 기준)
    private AccountBalance loadBalance(Strategy strategy, Account account) {
        AccountBalance balance = balanceLoader.loadBalanceOrThrow(strategy).balance();
        log.info("잔고 조회: [{}] {} {}주, 통합주문가능금액 ${}",
                account.nickname(), strategy.ticker().name(), balance.holdings(), balance.usdDeposit());
        return balance;
    }

    // creatableTimings 필터 후 TradingOrderSlots로 기존 주문과 동일 슬롯을 제외한다 (TradingPreviewService와 공유 기준)
    private List<PlannedOrder> filterCreatableOrders(List<PlannedOrder> plannedTemplates, List<Order> existingOrders,
                                              Set<OrderTiming> creatableTimings) {
        List<PlannedOrder> timingFiltered = plannedTemplates.stream()
                .filter(order -> creatableTimings.contains(order.timing()))
                .toList();
        return TradingOrderSlots.excludeExisting(timingFiltered, existingOrders);
    }

    // 사이클별 후보 수집 — 기존 주문은 보존하고 새 슬롯만 allocator 검증 대상으로 분리한다
    private CyclePlanCandidate collectCycleCandidate(BatchContext ctx,
            Map<StrategyTicker, PriceSnapshot> startPriceSnapshots, PrivacyTradeBase privacyBase,
            LocalDate tradeDate, Set<OrderTiming> creatableTimings) {
        Strategy strategy = ctx.strategy();
        Account account = ctx.account();
        AccountBalance balance = loadBalance(strategy, account);
        PriceSnapshot priceSnapshot = startPriceSnapshots.get(strategy.ticker());
        BigDecimal price = priceSnapshot != null ? priceSnapshot.current() : null;
        BigDecimal prevClosePrice = PriceSnapshot.prevCloseOrNull(priceSnapshot);
        List<Order> existingOrders = orderPort.findPlannedOrPlacedByCycleAndDate(ctx.currentCycle().id(), tradeDate);
        CycleOrderStrategy strategyHandler = cycleOrderStrategies.of(strategy.type());
        if (!existingOrders.isEmpty()
                && strategyHandler.canSkipOrderComputation(
                        existingOrders.stream().map(Order::toPlanned).toList(), creatableTimings)) {
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
        List<PlannedOrder> preparedOrders = priceCapper.prepareForAllocation(
                planOpt.get().orders(), price, planOpt.get().position(), planOpt.get().vrPosition(), strategy.ticker(),
                cycleOrderStrategies.of(strategy.type()).priceCapMode(), tradeDate);
        validateConcreteOrderLegs(strategy, preparedOrders);
        List<PlannedOrder> creatableOrders = filterCreatableOrders(
                preparedOrders, existingOrders, creatableTimings);
        PrivacyTradeBase privacyBaseForState = strategy.isPrivacy() ? privacyBase : null;
        CycleState state = new CycleState(ctx, balance, planOpt.get().position(), planOpt.get().vrPosition(), price, privacyBaseForState);
        return new CyclePlanCandidate(state, creatableOrders, !existingOrders.isEmpty());
    }

    private void validateConcreteOrderLegs(Strategy strategy, List<PlannedOrder> orders) {
        List<PlannedOrder> unknownLegOrders = orders.stream()
                .filter(order -> PlannedOrder.UNKNOWN_LEG.equals(order.orderLeg()))
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
            return new CycleState(ctx, balance, recalcPos, null, price, null);
        }
        // PRIVACY: price 전달 — capPrivacyIfNeeded에서 현재가 기반 BUY 가격 캡 적용
        // VR: privacyBase 오염 방지 (혼합 배치 시 hasPrivacy=true로 조회됐을 수 있음)
        // VR은 canSkipOrderComputation()이 항상 false라 이 메서드에 도달하지 않음 — vrPosition은 항상 null로 둔다
        PrivacyTradeBase privacyBaseForState = strategy.isPrivacy() ? privacyBase : null;
        return new CycleState(ctx, balance, null, null, price, privacyBaseForState);
    }

    // allocator 승인 주문만 PLANNED 저장하고 BUY/SELL 거절 사이클에는 기존 잔고 부족 알림을 재사용한다
    SaveAllocationResult saveAllocatedOrders(List<CyclePlanCandidate> candidates, LocalDate tradeDate)
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

        // 브로커 HTTP 조회(잔고·판매가능수량)는 계좌 간 선 병렬 일괄 조회로 분리한다.
        // 계좌 내 예산 차감(우선순위 순차 배정)은 순서 의존이라 아래 루프에서 그대로 순차 유지한다.
        TradingOrderBudgetAllocator.LiveQuotes liveQuotes =
                budgetAllocator.fetchLiveQuotes(List.copyOf(candidatesByAccount.values()));

        // 계좌별 예산 조회 실패가 다른 계좌의 주문 생성을 막지 않도록 격리한다.
        for (List<TradingOrderBudgetAllocator.Candidate> accountCandidates : candidatesByAccount.values()) {
            BatchContext firstContext = accountCandidates.getFirst().ctx();
            UUID accountId = firstContext.account().id();
            Optional<TradingOrderBudgetAllocator.Allocation> allocation = batchGuard.runSafely("계좌 주문 예산 배정", firstContext,
                    () -> budgetAllocator.allocate(accountCandidates, tradeDate, liveQuotes.require(accountId)));
            if (allocation.isPresent()) {
                allocations.add(allocation.get());
            }
        }

        for (TradingOrderBudgetAllocator.Allocation allocation : allocations) {
            for (TradingOrderBudgetAllocator.Candidate approved : allocation.approved()) {
                Optional<BatchContext> saved = batchGuard.runSafely("계획 주문 저장", approved.ctx(), () -> {
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
                batchGuard.runSafely("예수금 부족 알림", ctx, () -> {
                        eventPublisher.publishEvent(new InsufficientBalanceEvent(
                                ctx.user().id(), ctx.account().id(), null, ctx.strategy().ticker(), ctx.strategy().type()));
                        return null;
                    });
            }
        }

        return new SaveAllocationResult(Set.copyOf(savedContexts));
    }
}
