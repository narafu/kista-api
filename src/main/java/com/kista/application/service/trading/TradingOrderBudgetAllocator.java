package com.kista.application.service.trading;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.BatchContext;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.broker.LiveBalancePort;
import com.kista.domain.port.out.broker.SellableQuantityPort;
import com.kista.domain.strategy.CycleOrderStrategies;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.kista.domain.model.order.Order.OrderDirection.BUY;
import static com.kista.domain.model.order.Order.OrderDirection.SELL;

@Slf4j
@Component
@RequiredArgsConstructor
class TradingOrderBudgetAllocator {

    private final BrokerAdapterRegistry registry;              // live 잔고·판매가능수량 조회
    private final OrderPort orderPort;                         // 기존 PLANNED/PLACED 예약분 조회
    private final CycleOrderStrategies cycleOrderStrategies;    // 전략 타입별 예산 배정 우선순위 조회

    // Allocation input for one strategy cycle; orders may include BUY, SELL, or both.
    record Candidate(BatchContext ctx, List<Order> orders) {}

    // Approved contains only approved directions per candidate; rejected lists are direction-specific.
    record Allocation(List<Candidate> approved, List<Candidate> rejectedBuy, List<Candidate> rejectedSell) {}

    Allocation allocate(List<Candidate> candidates, LocalDate tradeDate) {
        if (candidates.isEmpty()) return new Allocation(List.of(), List.of(), List.of());

        SellAllocation sellAllocation = allocateSells(candidates, tradeDate);
        BuyAllocation buyAllocation = allocateBuysByAccount(candidates, tradeDate);
        List<Candidate> approved = mergeApproved(candidates, sellAllocation.approved(), buyAllocation.approved());
        return new Allocation(approved, buyAllocation.rejected(), sellAllocation.rejected());
    }

    private SellAllocation allocateSells(List<Candidate> candidates, LocalDate tradeDate) {
        Map<AccountTicker, List<SellRequest>> requestsByAccountTicker = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            Map<Strategy.Ticker, List<Order>> sellsByTicker = candidate.orders().stream()
                    .filter(order -> order.direction() == SELL)
                    .collect(java.util.stream.Collectors.groupingBy(
                            Order::ticker, LinkedHashMap::new, java.util.stream.Collectors.toList()));
            sellsByTicker.forEach((ticker, sells) -> requestsByAccountTicker
                    .computeIfAbsent(new AccountTicker(candidate.ctx().account().id(), ticker), ignored -> new ArrayList<>())
                    .add(new SellRequest(candidate, sells)));
        }

        List<Candidate> approved = new ArrayList<>();
        List<Candidate> rejected = new ArrayList<>();
        requestsByAccountTicker.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(accountTickerComparator()))
                .forEach(entry -> allocateSellsForAccountTicker(
                        entry.getKey(), entry.getValue(), tradeDate, approved, rejected));
        return new SellAllocation(approved, rejected);
    }

    private void allocateSellsForAccountTicker(AccountTicker accountTicker, List<SellRequest> requests, LocalDate tradeDate,
                                                List<Candidate> approved, List<Candidate> rejected) {
        List<SellRequest> sorted = requests.stream().sorted(sellPriorityComparator()).toList();
        Account account = sorted.getFirst().candidate().ctx().account();
        int sellableQuantity = registry.require(account, SellableQuantityPort.class)
                .getSellableQuantity(accountTicker.ticker(), account)
                .quantity();
        int reservedQuantity = orderPort.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(
                account.id(), tradeDate, accountTicker.ticker());
        int requestedQuantity = sorted.stream().mapToInt(request -> sellTotal(request.orders())).sum();
        int allocatedQuantity = 0;

        for (SellRequest request : sorted) {
            int requiredQuantity = sellTotal(request.orders());
            if (reservedQuantity + allocatedQuantity + requiredQuantity <= sellableQuantity) {
                approved.add(new Candidate(request.candidate().ctx(), request.orders()));
                allocatedQuantity += requiredQuantity;
                log.info("[{}] SELL 승인: ticker={}, required={}, reserved={}, allocated={}, sellable={}",
                        account.nickname(), accountTicker.ticker(), requiredQuantity, reservedQuantity,
                        allocatedQuantity, sellableQuantity);
            } else {
                rejected.add(new Candidate(request.candidate().ctx(), request.orders()));
                log.warn("[{}] SELL 판매가능수량 부족으로 제외: ticker={}, required={}, requestedTotal={}, reserved={}, allocated={}, sellable={}",
                        account.nickname(), accountTicker.ticker(), requiredQuantity, requestedQuantity,
                        reservedQuantity, allocatedQuantity, sellableQuantity);
            }
        }
    }

    private BuyAllocation allocateBuysByAccount(List<Candidate> candidates, LocalDate tradeDate) {
        Map<UUID, List<Candidate>> candidatesByAccount = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            List<Order> buys = candidate.orders().stream().filter(order -> order.direction() == BUY).toList();
            if (!buys.isEmpty()) {
                candidatesByAccount.computeIfAbsent(candidate.ctx().account().id(), ignored -> new ArrayList<>())
                        .add(new Candidate(candidate.ctx(), buys));
            }
        }

        List<Candidate> approved = new ArrayList<>();
        List<Candidate> rejected = new ArrayList<>();
        candidatesByAccount.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> allocateBuysForAccount(entry.getValue(), tradeDate, approved, rejected));
        return new BuyAllocation(approved, rejected);
    }

    private void allocateBuysForAccount(List<Candidate> candidates, LocalDate tradeDate,
                                        List<Candidate> approved, List<Candidate> rejected) {
        List<Candidate> sorted = candidates.stream().sorted(buyPriorityComparator()).toList();
        Account account = sorted.getFirst().ctx().account();
        Strategy budgetProbeStrategy = sorted.getFirst().ctx().strategy();
        AccountBalance live = registry.require(account, LiveBalancePort.class)
                .getLiveBalance(account, budgetProbeStrategy.ticker());
        BigDecimal reservedBuy = orderPort.sumPlannedBuyByAccountAndDate(account.id(), tradeDate);
        BigDecimal allocatedInBatch = BigDecimal.ZERO;

        for (Candidate candidate : sorted) {
            BigDecimal required = buyTotal(candidate.orders());
            BigDecimal alreadyCommitted = reservedBuy.add(allocatedInBatch);
            if (live.hasSufficientDepositFor(candidate.orders(), alreadyCommitted)) {
                approved.add(candidate);
                allocatedInBatch = allocatedInBatch.add(required);
                log.info("[{}] BUY 예산 배정: strategy={}, required={}, remaining={}",
                        account.nickname(), candidate.ctx().strategy().type(), required,
                        remainingDeposit(live, reservedBuy, allocatedInBatch));
            } else {
                rejected.add(candidate);
                log.warn("[{}] BUY 예산 부족으로 제외: strategy={}, required={}, remaining={}",
                        account.nickname(), candidate.ctx().strategy().type(), required,
                        remainingDeposit(live, reservedBuy, allocatedInBatch));
            }
        }
    }

    private Comparator<Candidate> buyPriorityComparator() {
        return Comparator
                .comparingInt((Candidate candidate) -> strategyPriority(candidate.ctx().strategy().type()))
                .thenComparing(candidate -> buyTotal(candidate.orders()))
                .thenComparing(candidate -> candidate.ctx().strategy().id())
                .thenComparing(candidate -> candidate.ctx().currentCycle().id());
    }

    private Comparator<SellRequest> sellPriorityComparator() {
        return Comparator
                .comparingInt((SellRequest request) -> strategyPriority(request.candidate().ctx().strategy().type()))
                .thenComparingInt(request -> sellTotal(request.orders()))
                .thenComparing(request -> request.candidate().ctx().strategy().id())
                .thenComparing(request -> request.candidate().ctx().currentCycle().id());
    }

    private Comparator<AccountTicker> accountTickerComparator() {
        return Comparator.comparing(AccountTicker::accountId)
                .thenComparing(AccountTicker::ticker);
    }

    private int strategyPriority(Strategy.Type type) {
        return cycleOrderStrategies.of(type).allocationPriority();
    }

    private BigDecimal buyTotal(List<Order> orders) {
        return AccountBalance.buyTotal(orders);
    }

    private int sellTotal(List<Order> orders) {
        return orders.stream().mapToInt(Order::quantity).sum();
    }

    private BigDecimal remainingDeposit(AccountBalance live, BigDecimal reservedBuy, BigDecimal allocatedInBatch) {
        return live.usdDeposit().subtract(reservedBuy).subtract(allocatedInBatch);
    }

    private List<Candidate> mergeApproved(List<Candidate> candidates,
                                          List<Candidate> sellApproved,
                                          List<Candidate> buyApproved) {
        Map<BatchContext, Candidate> sourceCandidates = new LinkedHashMap<>();
        candidates.forEach(candidate -> sourceCandidates.putIfAbsent(candidate.ctx(), candidate));

        Map<BatchContext, EnumSet<Order.OrderDirection>> approvedDirections = new LinkedHashMap<>();
        Stream.concat(sellApproved.stream(), buyApproved.stream())
                .forEach(candidate -> candidate.orders().forEach(order -> approvedDirections
                        .computeIfAbsent(candidate.ctx(), ignored -> EnumSet.noneOf(Order.OrderDirection.class))
                        .add(order.direction())));

        return Stream.concat(sellApproved.stream(), buyApproved.stream())
                .map(Candidate::ctx)
                .distinct()
                .map(ctx -> new Candidate(ctx, sourceCandidates.get(ctx).orders().stream()
                        .filter(order -> approvedDirections.get(ctx).contains(order.direction()))
                        .toList()))
                .filter(candidate -> !candidate.orders().isEmpty())
                .toList();
    }

    private record AccountTicker(UUID accountId, Strategy.Ticker ticker) {}

    private record SellRequest(Candidate candidate, List<Order> orders) {}

    private record SellAllocation(List<Candidate> approved, List<Candidate> rejected) {}

    private record BuyAllocation(List<Candidate> approved, List<Candidate> rejected) {}
}
