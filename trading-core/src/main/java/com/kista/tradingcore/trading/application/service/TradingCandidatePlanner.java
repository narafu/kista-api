package com.kista.tradingcore.trading.application.service;
import com.kista.tradingcore.trading.application.service.support.TradingBalanceLoader;
import com.kista.tradingcore.trading.application.service.support.TradingBatchGuard;
import com.kista.tradingcore.trading.application.service.support.TradingOrderPlanner;
import com.kista.tradingcore.trading.application.service.support.TradingParallelRunner;

import com.kista.tradingcore.trading.domain.model.TradingAccount;
import com.kista.tradingcore.broker.domain.model.PriceSnapshot;
import com.kista.tradingcore.matching.domain.model.*;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategy;
import com.kista.tradingcore.privacy.domain.model.PrivacyTradeBase;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.trading.application.event.InsufficientBalanceEvent;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.Strategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

// 전략별 후보 수집(잔고 로드 + 전략 계산 + 가격 캡) + 전략별 원장 상한 적용 — executeBatch(AT_CLOSE)/placeOpenOrders(AT_OPEN) 공용
@Component
@RequiredArgsConstructor
@Slf4j
class TradingCandidatePlanner {

    private final OrderPort orderPort;
    private final CycleOrderComputer orderComputer;
    private final TradingOrderPlanner orderPlanner;
    private final BuyOrderPriceCapper priceCapper;
    private final CycleOrderStrategies cycleOrderStrategies;
    private final StrategyLedgerBudget ledgerBudget;           // 전략 자기 원장 상한 — 계좌 예산 배정 대체
    private final TradingBalanceLoader balanceLoader;
    private final ApplicationEventPublisher eventPublisher; // 예수금 부족 알림(InsufficientBalanceEvent)
    private final TradingBatchGuard batchGuard;
    private final AccountBudgetLock budgetLock;                 // 계좌별 원장 판정~저장 직렬화 — 수동 실행·재캡과 같은 락
    private final DcaPlanInputCollector dcaInputCollector;      // 회차 판정 전략(DCA) plan 입력 수집 — 부작용 없음
    private final DcaExecutionRecorder dcaExecutionRecorder;    // DCA 회차 이력·skip 알림 기록
    private final TradingParallelRunner parallelRunner;         // 계좌별 원장 판정+저장 병렬 실행

    // 슬롯별 후보 수집 결과: 전략별 잔고·전략 계산 상태
    record CycleState(
            BatchContext ctx,
            AccountBalance balance,
            InfinitePosition position,      // INFINITE만 non-null (신규 계산 시 — pre-existing skip 케이스는 null)
            VrPosition vrPosition,          // VR만 non-null (신규 계산 시 — VrCycleOrderStrategy.capBuyOrders() 보정용)
            BigDecimal startPrice,          // 배치 시작 시점 가격 — placeAll()에서 접수 직전 재조회(reloadPlacementPrices) 실패 시 폴백으로만 사용
            PrivacyTradeBase privacyBase    // PRIVACY만 non-null (rotation 시 최소금액 산정용)
    ) {}

    // 전략 계산 결과 중 신규 생성 가능한 주문만 allocator에 전달하기 위한 후보
    record CyclePlanCandidate(
            CycleState state,
            List<PlannedOrder> creatableOrders,
            boolean hasExistingOrders,
            Map<String, BigDecimal> dcaReferencePrices // DCA 신규 계획만 — 주문 leg → 주문 기준가(이력 reference_price 기록용)
    ) {
        CyclePlanCandidate(CycleState state, List<PlannedOrder> creatableOrders, boolean hasExistingOrders) {
            this(state, creatableOrders, hasExistingOrders, Map.of());
        }
    }

    // 예산 배정 후 실제 저장된 컨텍스트만 다음 단계 진입 대상으로 사용한다
    record SaveAllocationResult(Set<BatchContext> savedContexts) {}

    // 전략별 후보를 먼저 수집하고 계좌별 예산 배정 후 AT_CLOSE 주문만 저장
    List<CycleState> planAll(List<BatchContext> contexts, Map<StrategyTicker, PriceSnapshot> startPriceSnapshots,
                              PrivacyTradeBase privacyBase, LocalDate today) throws InterruptedException {
        List<CyclePlanCandidate> candidates = new ArrayList<>();
        for (BatchContext ctx : contexts) {
            batchGuard.runSafely("주문 계획", ctx,
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
            batchGuard.runSafely("개장 주문 계획", ctx,
                    () -> collectCycleCandidate(ctx, startPriceSnapshots, privacyBase, tradeDate,
                            EnumSet.of(OrderTiming.AT_OPEN)))
                    .ifPresent(candidates::add);
        }
        return candidates;
    }

    // 잔고 로드 — KIS·Toss 모두 cycle_position DB 이력 사용 (전략 공식 기준)
    private AccountBalance loadBalance(Strategy strategy, TradingAccount account) {
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
        TradingAccount account = ctx.account();
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
        // 회차 판정 전략(DCA)은 규칙 입력을 수집해 plan()에 넘기고, 이력 없이 남은 당일 규칙 주문은 ORDERED로 복구 기록한다
        // 원장(balance)이 규칙 판정의 AVG_PRICE 평단·SELL 보유 상한 기준
        DcaCollected dca = strategyHandler.recordsRounds()
                ? dcaInputCollector.collect(ctx, tradeDate, creatableTimings, existingOrders, price, balance)
                : null;
        if (dca != null) {
            dca.recoveries().forEach((ruleKey, order) -> dcaExecutionRecorder.recordOrdered(ctx, tradeDate, ruleKey, order, null));
        }
        Optional<CycleOrderStrategy.OrderPlan> planOpt = orderComputer.compute(balance, strategy, prevClosePrice, tradeDate,
                ctx.currentCycle(), privacyBase, account.nickname(), price, dca == null ? null : dca.inputs());
        if (planOpt.isEmpty()) {
            log.info("[{}] 전략 계산 skip (PRIVACY 기준 미수신 등)", account.nickname());
            if (existingOrders.isEmpty()) return null;
            CycleState existingState = buildCycleStateFromExistingOrders(
                    ctx, balance, priceSnapshot, privacyBase, tradeDate, existingOrders.size(), true);
            return new CyclePlanCandidate(existingState, List.of(), true);
        }
        if (dca != null) recordDcaSkips(ctx, tradeDate, planOpt.get().skips(), dca.inputs());

        // 예산 배정 전에 전략별 가격 cap을 반영해 최종 BUY 수량과 correction 주문까지 포함한다.
        List<PlannedOrder> preparedOrders = strategyHandler.fixesPricesAtPlan()
                ? planOpt.get().orders() // 계획 시점 캡가 고정 — 현재가 기준 재캡 없음
                : priceCapper.prepareForAllocation(
                        planOpt.get().orders(), price, planOpt.get().position(), planOpt.get().vrPosition(), strategy.ticker(),
                        strategy.type(), tradeDate);
        validateConcreteOrderLegs(strategy, preparedOrders);
        List<PlannedOrder> creatableOrders = filterCreatableOrders(
                preparedOrders, existingOrders, creatableTimings);
        PrivacyTradeBase privacyBaseForState = strategy.isPrivacy() ? privacyBase : null;
        CycleState state = new CycleState(ctx, balance, planOpt.get().position(), planOpt.get().vrPosition(), price, privacyBaseForState);
        return new CyclePlanCandidate(state, creatableOrders, !existingOrders.isEmpty(), planOpt.get().referencePrices());
    }

    // 규칙 skip 판정 이력 기록 — 방향은 판정 입력의 규칙에서 찾는다(ruleKey는 버전 내 유일)
    private void recordDcaSkips(BatchContext ctx, LocalDate tradeDate, List<DcaRuleDecision.Skipped> skips,
                                CycleOrderStrategy.PlanContext.DcaPlanInputs inputs) {
        Map<String, OrderDirection> directions = inputs.dueRules().stream()
                .collect(Collectors.toMap(in -> in.rule().ruleKey(), in -> in.rule().direction()));
        for (DcaRuleDecision.Skipped skip : skips) {
            dcaExecutionRecorder.recordSkipped(ctx, tradeDate, skip.ruleKey(), directions.get(skip.ruleKey()),
                    skip.reason(), skip.detail(), skip.referencePrice());
        }
    }

    private boolean recordsRounds(BatchContext ctx) {
        return cycleOrderStrategies.of(ctx.strategy().type()).recordsRounds();
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
        TradingAccount account = ctx.account();
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
        // PRIVACY: price 전달 — BuyOrderPriceCapper.capIfNeeded(type=PRIVACY, ...)에서 현재가 기반 BUY 가격 캡 적용
        // VR: privacyBase 오염 방지 (혼합 배치 시 hasPrivacy=true로 조회됐을 수 있음)
        // VR은 canSkipOrderComputation()이 항상 false라 이 메서드에 도달하지 않음 — vrPosition은 항상 null로 둔다
        PrivacyTradeBase privacyBaseForState = strategy.isPrivacy() ? privacyBase : null;
        return new CycleState(ctx, balance, null, null, price, privacyBaseForState);
    }

    // 전략 원장 상한을 통과한 주문만 PLANNED 저장하고 BUY가 거절된 사이클에는 기존 잔고 부족 알림을 재사용한다
    SaveAllocationResult saveAllocatedOrders(List<CyclePlanCandidate> candidates, LocalDate tradeDate)
            throws InterruptedException {
        Map<UUID, List<Slot>> slotsByAccount = new LinkedHashMap<>();
        candidates.stream()
                .filter(candidate -> !candidate.creatableOrders().isEmpty())
                .map(candidate -> new Slot(candidate.state().ctx(), candidate.creatableOrders()))
                .forEach(slot -> slotsByAccount
                        .computeIfAbsent(slot.ctx().account().id(), _ -> new ArrayList<>())
                        .add(slot));
        // 회차 판정 사이클별 leg → 주문 기준가 — ORDERED 이력의 reference_price (병렬 태스크는 읽기만 한다)
        Map<BatchContext, Map<String, BigDecimal>> dcaReferencePrices = candidates.stream()
                .filter(candidate -> !candidate.dcaReferencePrices().isEmpty())
                .collect(Collectors.toMap(candidate -> candidate.state().ctx(), CyclePlanCandidate::dcaReferencePrices, (a, b) -> a));

        Set<BatchContext> savedContexts = new LinkedHashSet<>();

        // 계좌 간 병렬 태스크로 원장 판정+PLANNED 저장을 묶는다. 판정~저장 커밋은 계좌 락 안 —
        // 같은 계좌의 수동 실행·접수 직전 재캡과 저장 슬롯을 이중으로 세지 않게 한다
        List<TradingParallelRunner.Task<AccountAllocation>> tasks = slotsByAccount.values().stream()
                .map(accountSlots -> {
                    BatchContext firstContext = accountSlots.getFirst().ctx();
                    UUID accountId = firstContext.account().id();
                    return new TradingParallelRunner.Task<AccountAllocation>(accountId,
                            () -> budgetLock.call(accountId, () -> allocateAndSave(accountSlots, firstContext, tradeDate, dcaReferencePrices)));
                })
                .toList();

        for (AccountAllocation accountAllocation : parallelRunner.runAll(tasks)) {
            savedContexts.addAll(accountAllocation.savedContexts());
            for (BatchContext ctx : accountAllocation.rejectedContexts()) {
                batchGuard.runSafely("예수금 부족 알림", ctx, () -> {
                        eventPublisher.publishEvent(new InsufficientBalanceEvent(
                                ctx.userProfile().userId(), ctx.account().id(), ctx.account().nickname(),
                                0, null, ctx.strategy().ticker(), ctx.strategy().type(), 0, false));
                        return null;
                    });
            }
        }

        return new SaveAllocationResult(Set.copyOf(savedContexts));
    }

    // 저장된 DCA 주문을 조회해 규칙별 ORDERED 이력 기록 — saveAll이 id를 돌려주지 않아 당일 PLANNED/PLACED에서 DCA leg로 찾는다
    // referencePrices: leg → 판정 시점 주문 기준가(BUY=max(전일 종가, 현재가), SELL=전일 종가) — 캡가 역산 대신 후보에 실어 온 값
    private void recordDcaOrdered(BatchContext ctx, LocalDate tradeDate, Map<String, BigDecimal> referencePrices) throws InterruptedException {
        batchGuard.runSafely("DCA 회차 이력 기록", ctx, () -> {
            orderPort.findPlannedOrPlacedByCycleAndDate(ctx.currentCycle().id(), tradeDate).stream()
                    .filter(order -> order.orderLeg().startsWith(DCA_LEG_PREFIX))
                    .forEach(order -> dcaExecutionRecorder.recordOrdered(ctx, tradeDate, dcaRuleKey(order.orderLeg()), order,
                            referencePrices.get(order.orderLeg())));
            return ctx;
        });
    }

    private static final String DCA_LEG_PREFIX = "DCA_"; // DCA 주문 leg = "DCA_" + ruleKey

    private static String dcaRuleKey(String orderLeg) {
        return orderLeg.substring(DCA_LEG_PREFIX.length());
    }

    // 원장 판정 대상 슬롯 — 사이클(ctx)과 신규 생성 가능한 주문
    private record Slot(BatchContext ctx, List<PlannedOrder> orders) {}

    // 계좌 1개의 저장 성공 사이클과 원장 상한으로 BUY가 거절된 사이클
    private record AccountAllocation(List<BatchContext> savedContexts, List<BatchContext> rejectedContexts) {}

    // 계좌 예산 락 안에서 실행 — 사이클마다 저장 직전 원장 잔여를 계산하고(이미 저장된 슬롯은 한 번만 차감), 통과분을 저장한다
    private Optional<AccountAllocation> allocateAndSave(List<Slot> accountSlots, BatchContext firstContext,
                                                        LocalDate tradeDate,
                                                        Map<BatchContext, Map<String, BigDecimal>> dcaReferencePrices)
            throws InterruptedException {
        List<BatchContext> saved = new ArrayList<>();
        List<BatchContext> rejected = new ArrayList<>();
        for (Slot slot : accountSlots) {
            BatchContext ctx = slot.ctx();
            // 후보 수집(락 밖) 이후 같은 사이클에 수동 실행이 저장한 슬롯은 제외 — 중복 주문 방지.
            // 전부 겹친 사이클은 이미 PLANNED가 있으므로 판정 없이 접수 대상(saved)에 넣는다
            Optional<List<PlannedOrder>> fresh = batchGuard.runSafely("기존 주문 재확인", ctx, () -> TradingOrderSlots.excludeExisting(
                    slot.orders(), orderPort.findPlannedOrPlacedByCycleAndDate(ctx.currentCycle().id(), tradeDate)));
            if (fresh.isEmpty()) continue;
            if (fresh.get().isEmpty()) {
                saved.add(ctx);
                continue;
            }
            Optional<BatchContext> savedCtx = batchGuard.runSafely("원장 판정·계획 주문 저장", ctx, () -> {
                // 저장 직전 원장 잔여 — 위 재확인으로 걸러진 신규 주문만 판정하므로 이미 저장된 슬롯은 remaining에서 한 번만 빠진다
                StrategyLedgerBudget.LedgerFit fit = ledgerBudget.fit(ctx.strategy().type(), fresh.get(),
                        ledgerBudget.remaining(ctx.currentCycle(), tradeDate));
                if (!fit.orders().isEmpty()) {
                    orderPlanner.savePlannedOrders(fit.orders(), ctx.account(), ctx.currentCycle().id());
                }
                if (fit.rejected()) rejected.add(ctx); // 저장 성공 뒤에만 알림 대상으로 — 저장 실패는 runSafely가 별도 알림
                return fit.orders().isEmpty() ? null : ctx; // SELL 없이 BUY만 거절된 사이클은 저장 대상 아님
            });
            savedCtx.ifPresent(saved::add);
            if (savedCtx.isPresent() && recordsRounds(ctx)) {
                recordDcaOrdered(ctx, tradeDate, dcaReferencePrices.getOrDefault(ctx, Map.of())); // 저장 성공 후에만 — 실패해도 접수는 진행, 다음 배치 복구
            }
        }
        return Optional.of(new AccountAllocation(saved, rejected));
    }
}
