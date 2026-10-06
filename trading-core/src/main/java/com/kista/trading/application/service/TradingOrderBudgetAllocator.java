package com.kista.trading.application.service;

import com.kista.trading.domain.model.TradingAccount;
import com.kista.matching.domain.model.PlannedOrder;
import com.kista.matching.domain.model.AccountBalance;
import com.kista.trading.domain.model.BatchContext;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.broker.domain.model.BrokerBalance;
import com.kista.broker.application.port.output.LiveBalancePort;
import com.kista.broker.application.port.output.SellableQuantityPort;
import com.kista.matching.domain.strategy.CycleOrderStrategies;
import com.kista.matching.domain.strategy.PriceCapPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.kista.sharedkernel.OrderDirection.BUY;
import static com.kista.sharedkernel.OrderDirection.SELL;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;

@Slf4j
@Component
@RequiredArgsConstructor
class TradingOrderBudgetAllocator {

    private final LiveBalancePort liveBalancePort; // live 잔고·판매가능수량 조회

    private final SellableQuantityPort sellableQuantityPort;
    private final OrderPort orderPort;                         // 기존 PLANNED/PLACED 예약분 조회
    private final CycleOrderStrategies cycleOrderStrategies;    // 전략 타입별 예산 배정 우선순위 조회

    // Allocation input for one strategy cycle; orders may include BUY, SELL, or both.
    record Candidate(BatchContext ctx, List<PlannedOrder> orders) {
        // 주문 목록만 교체 — BUY/SELL 분리, 배정 승인/거절 등 ctx는 그대로 두고 orders만 바꾸는 재구성에 사용
        Candidate withOrders(List<PlannedOrder> newOrders) {
            return new Candidate(ctx, newOrders);
        }
    }

    // Approved contains only approved directions per candidate; rejected lists are direction-specific.
    record Allocation(List<Candidate> approved, List<Candidate> rejectedBuy, List<Candidate> rejectedSell) {}

    // 계좌 단위 브로커 조회 결과 — allocate() 내부에서 즉시 조회해 바로 소비(선조회 캐시 아님)
    private record AccountQuote(AccountBalance liveBalance, Map<StrategyTicker, Integer> sellableByTicker) {}

    // 한 계좌 스코프의 브로커 선조회 — 잔고는 BUY 후보 존재 시만, 판매가능수량은 SELL 후보의 종목별로만 조회한다
    // candidates는 항상 단일 계좌 스코프여야 한다(호출부가 이미 계좌별로 묶어서 넘긴다)
    private AccountQuote fetchQuote(List<Candidate> accountCandidates) {
        TradingAccount account = accountCandidates.getFirst().ctx().account();

        List<Candidate> buyCandidates = accountCandidates.stream()
                .map(candidate -> candidate.withOrders(
                        candidate.orders().stream().filter(order -> order.direction() == BUY).toList()))
                .filter(candidate -> !candidate.orders().isEmpty())
                .toList();
        AccountBalance liveBalance = null;
        if (!buyCandidates.isEmpty()) {
            Candidate probe = buyCandidates.stream().sorted(buyPriorityComparator()).findFirst().orElseThrow();
            BrokerBalance bb = liveBalancePort
                    .getLiveBalance(account.brokerRef(), probe.ctx().strategy().ticker());
            liveBalance = new AccountBalance(bb.holdings(), bb.avgPrice(), bb.usdDeposit());
        }

        Set<StrategyTicker> sellTickers = accountCandidates.stream()
                .flatMap(candidate -> candidate.orders().stream())
                .filter(order -> order.direction() == SELL)
                .map(PlannedOrder::ticker)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<StrategyTicker, Integer> sellableByTicker = new LinkedHashMap<>();
        for (StrategyTicker ticker : sellTickers) {
            int sellable = sellableQuantityPort
                    .getSellableQuantity(ticker, account.brokerRef())
                    .quantity();
            sellableByTicker.put(ticker, sellable);
        }
        return new AccountQuote(liveBalance, sellableByTicker);
    }

    // candidates는 반드시 단일 계좌 스코프 — 조회+배정을 한 번에 수행한다
    Allocation allocate(List<Candidate> candidates, LocalDate tradeDate) {
        if (candidates.isEmpty()) return new Allocation(List.of(), List.of(), List.of());
        // PLANNED BUY 예약 합계를 live보다 먼저 읽는다 — 락 밖 접수(PLANNED→PLACED)가 두 조회 사이에 끼면
        // 반대 순서는 그 주문을 live·합계 양쪽에서 빠뜨려 과다 승인하고, 이 순서는 이중 차감(보수적)될 뿐이다
        BigDecimal reservedBuy = hasBuy(candidates)
                ? orderPort.sumPlannedBuyByAccountAndDate(candidates.getFirst().ctx().account().id(), tradeDate)
                : BigDecimal.ZERO;
        AccountQuote quote = fetchQuote(candidates);

        SellAllocation sellAllocation = allocateSells(candidates, tradeDate, quote);
        BuyAllocation buyAllocation = allocateBuys(candidates, reservedBuy, quote);
        List<Candidate> approved = mergeApproved(candidates, sellAllocation.approved(), buyAllocation.approved());
        return new Allocation(approved, buyAllocation.rejected(), sellAllocation.rejected());
    }

    private SellAllocation allocateSells(List<Candidate> candidates, LocalDate tradeDate, AccountQuote quote) {
        Map<StrategyTicker, List<SellRequest>> requestsByTicker = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            Map<StrategyTicker, List<PlannedOrder>> sellsByTicker = candidate.orders().stream()
                    .filter(order -> order.direction() == SELL)
                    .collect(java.util.stream.Collectors.groupingBy(
                            PlannedOrder::ticker, LinkedHashMap::new, java.util.stream.Collectors.toList()));
            sellsByTicker.forEach((ticker, sells) -> requestsByTicker
                    .computeIfAbsent(ticker, ignored -> new ArrayList<>())
                    .add(new SellRequest(candidate, sells)));
        }

        List<Candidate> approved = new ArrayList<>();
        List<Candidate> rejected = new ArrayList<>();
        requestsByTicker.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> allocateSellsForTicker(entry.getKey(), entry.getValue(), tradeDate, quote, approved, rejected));
        return new SellAllocation(approved, rejected);
    }

    private void allocateSellsForTicker(StrategyTicker ticker, List<SellRequest> requests, LocalDate tradeDate,
                                                AccountQuote quote, List<Candidate> approved, List<Candidate> rejected) {
        List<SellRequest> sorted = requests.stream().sorted(sellPriorityComparator()).toList();
        TradingAccount account = sorted.getFirst().candidate().ctx().account();
        Integer sellableQuantityBoxed = quote.sellableByTicker().get(ticker);
        if (sellableQuantityBoxed == null) {
            throw new IllegalStateException("판매가능수량 선조회 결과 없음: accountId=" + account.id() + ", ticker=" + ticker);
        }
        int sellableQuantity = sellableQuantityBoxed;
        int reservedQuantity = orderPort.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(
                account.id(), tradeDate, ticker);
        int requestedQuantity = sorted.stream().mapToInt(request -> sellTotal(request.orders())).sum();
        int allocatedQuantity = 0;

        for (SellRequest request : sorted) {
            int requiredQuantity = sellTotal(request.orders());
            if (reservedQuantity + allocatedQuantity + requiredQuantity <= sellableQuantity) {
                approved.add(request.candidate().withOrders(request.orders()));
                allocatedQuantity += requiredQuantity;
                log.info("[{}] SELL 승인: ticker={}, required={}, reserved={}, allocated={}, sellable={}",
                        account.nickname(), ticker, requiredQuantity, reservedQuantity, allocatedQuantity, sellableQuantity);
            } else {
                rejected.add(request.candidate().withOrders(request.orders()));
                log.warn("[{}] SELL 판매가능수량 부족으로 제외: ticker={}, required={}, requestedTotal={}, reserved={}, allocated={}, sellable={}",
                        account.nickname(), ticker, requiredQuantity, requestedQuantity, reservedQuantity, allocatedQuantity, sellableQuantity);
            }
        }
    }

    private BuyAllocation allocateBuys(List<Candidate> candidates, BigDecimal reservedBuy, AccountQuote quote) {
        List<Candidate> buyCandidates = candidates.stream()
                .map(candidate -> candidate.withOrders(
                        candidate.orders().stream().filter(order -> order.direction() == BUY).toList()))
                .filter(candidate -> !candidate.orders().isEmpty())
                .toList();
        if (buyCandidates.isEmpty()) return new BuyAllocation(List.of(), List.of());

        List<Candidate> sorted = buyCandidates.stream().sorted(buyPriorityComparator()).toList();
        TradingAccount account = sorted.getFirst().ctx().account();
        AccountBalance live = quote.liveBalance();
        if (live == null) {
            throw new IllegalStateException("BUY 잔고 선조회 결과 없음: accountId=" + account.id());
        }
        BigDecimal allocatedInBatch = BigDecimal.ZERO;

        List<Candidate> approved = new ArrayList<>();
        List<Candidate> rejected = new ArrayList<>();
        for (Candidate candidate : sorted) {
            BigDecimal required = buyTotal(candidate.orders());
            BigDecimal alreadyCommitted = reservedBuy.add(allocatedInBatch);
            if (live.hasSufficientDepositFor(candidate.orders(), alreadyCommitted)) {
                approved.add(candidate);
                allocatedInBatch = allocatedInBatch.add(required);
                log.info("[{}] BUY 예산 배정: strategy={}, required={}, remaining={}",
                        account.nickname(), candidate.ctx().strategy().type(), required,
                        remainingDeposit(live, reservedBuy, allocatedInBatch));
                continue;
            }
            // 전체는 못 담을 때 전략이 허용하는 축소안(예: INFINITE 보정 주문 생략)이 예산 안이면 그것으로 승인
            BigDecimal budget = live.usdDeposit().subtract(alreadyCommitted);
            List<PlannedOrder> fitted = cycleOrderStrategies.of(candidate.ctx().strategy().type())
                    .fitBuysToBudget(candidate.orders(), budget);
            if (!fitted.isEmpty() && buyTotal(fitted).compareTo(budget) <= 0) {
                approved.add(candidate.withOrders(fitted));
                allocatedInBatch = allocatedInBatch.add(buyTotal(fitted));
                log.info("[{}] BUY 예산 내 축소 승인: strategy={}, required={}, fitted={}, orders {}->{}건",
                        account.nickname(), candidate.ctx().strategy().type(), required, buyTotal(fitted),
                        candidate.orders().size(), fitted.size());
            } else {
                rejected.add(candidate);
                log.warn("[{}] BUY 예산 부족으로 제외: strategy={}, required={}, remaining={}",
                        account.nickname(), candidate.ctx().strategy().type(), required,
                        remainingDeposit(live, reservedBuy, allocatedInBatch));
            }
        }
        return new BuyAllocation(approved, rejected);
    }

    private Comparator<Candidate> buyPriorityComparator() {
        return BuyPriorityOrdering.comparator(cycleOrderStrategies,
                candidate -> candidate.ctx().strategy().type(),
                candidate -> buyTotal(candidate.orders()),
                candidate -> candidate.ctx().strategy().id(),
                candidate -> candidate.ctx().currentCycle().id());
    }

    private Comparator<SellRequest> sellPriorityComparator() {
        return Comparator
                .comparingInt((SellRequest request) -> strategyPriority(request.candidate().ctx().strategy().type()))
                .thenComparingInt(request -> sellTotal(request.orders()))
                .thenComparing(request -> request.candidate().ctx().strategy().id())
                .thenComparing(request -> request.candidate().ctx().currentCycle().id());
    }

    private int strategyPriority(StrategyType type) {
        return cycleOrderStrategies.of(type).allocationPriority();
    }

    private boolean hasBuy(List<Candidate> candidates) {
        return candidates.stream().flatMap(candidate -> candidate.orders().stream())
                .anyMatch(order -> order.direction() == BUY);
    }

    private BigDecimal buyTotal(List<PlannedOrder> orders) {
        return AccountBalance.buyTotal(orders);
    }

    private int sellTotal(List<PlannedOrder> orders) {
        return orders.stream().mapToInt(PlannedOrder::quantity).sum();
    }

    private BigDecimal remainingDeposit(AccountBalance live, BigDecimal reservedBuy, BigDecimal allocatedInBatch) {
        return live.usdDeposit().subtract(reservedBuy).subtract(allocatedInBatch);
    }

    private List<Candidate> mergeApproved(List<Candidate> candidates,
                                          List<Candidate> sellApproved,
                                          List<Candidate> buyApproved) {
        Map<BatchContext, Candidate> sourceCandidates = new LinkedHashMap<>();
        candidates.forEach(candidate -> sourceCandidates.putIfAbsent(candidate.ctx(), candidate));

        // 승인 SELL은 원본 인스턴스 그대로, 승인 BUY는 축소본(주문 제외·수량 축소)일 수 있어 ctx별로 따로 모은다
        Map<BatchContext, List<PlannedOrder>> approvedSells = new LinkedHashMap<>();
        sellApproved.forEach(candidate -> approvedSells
                .computeIfAbsent(candidate.ctx(), ignored -> new ArrayList<>())
                .addAll(candidate.orders()));
        Map<BatchContext, List<PlannedOrder>> approvedBuys = new LinkedHashMap<>();
        buyApproved.forEach(candidate -> approvedBuys.put(candidate.ctx(), candidate.orders()));

        // 결과는 승인 순서(SELL 승인 → BUY 승인) 그대로, 후보 내부는 원본 순서 기준 — 승인 SELL만 남기고
        // BUY 자리는 승인 BUY 목록으로 순서대로 교체한다(equals 대조면 VR 수량 축소 주문 같은 새 인스턴스가 조용히 빠진다)
        Set<BatchContext> approvedContexts = new LinkedHashSet<>(approvedSells.keySet());
        approvedContexts.addAll(approvedBuys.keySet());
        return approvedContexts.stream()
                .map(ctx -> {
                    Candidate source = sourceCandidates.get(ctx);
                    List<PlannedOrder> sells = approvedSells.getOrDefault(ctx, List.of());
                    List<PlannedOrder> kept = source.orders().stream()
                            .filter(order -> order.direction() == BUY || sells.contains(order))
                            .toList();
                    return source.withOrders(PriceCapPolicy.replaceBuysPreservingOrder(
                            kept, approvedBuys.getOrDefault(ctx, List.of())));
                })
                .filter(candidate -> !candidate.orders().isEmpty())
                .toList();
    }

    private record SellRequest(Candidate candidate, List<PlannedOrder> orders) {}

    private record SellAllocation(List<Candidate> approved, List<Candidate> rejected) {}

    private record BuyAllocation(List<Candidate> approved, List<Candidate> rejected) {}
}
