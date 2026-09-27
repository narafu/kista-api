package com.kista.trading.application.service;

import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.account.domain.model.Account;
import com.kista.matching.domain.model.PlannedOrder;
import com.kista.sharedkernel.OrderDirection;
import com.kista.matching.domain.model.AccountBalance;
import com.kista.trading.domain.model.BatchContext;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.broker.domain.model.BrokerBalance;
import com.kista.broker.application.port.output.LiveBalancePort;
import com.kista.broker.application.port.output.SellableQuantityPort;
import com.kista.matching.domain.strategy.CycleOrderStrategies;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.kista.sharedkernel.OrderDirection.BUY;
import static com.kista.sharedkernel.OrderDirection.SELL;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;

@Slf4j
@Component
@RequiredArgsConstructor
class TradingOrderBudgetAllocator {

    private final BrokerAdapterRegistry registry;              // live 잔고·판매가능수량 조회
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
        Account account = accountCandidates.getFirst().ctx().account();

        List<Candidate> buyCandidates = accountCandidates.stream()
                .map(candidate -> candidate.withOrders(
                        candidate.orders().stream().filter(order -> order.direction() == BUY).toList()))
                .filter(candidate -> !candidate.orders().isEmpty())
                .toList();
        AccountBalance liveBalance = null;
        if (!buyCandidates.isEmpty()) {
            Candidate probe = buyCandidates.stream().sorted(buyPriorityComparator()).findFirst().orElseThrow();
            BrokerBalance bb = registry.require(account.toBrokerRef(), LiveBalancePort.class)
                    .getLiveBalance(account.toBrokerRef(), probe.ctx().strategy().ticker());
            liveBalance = new AccountBalance(bb.holdings(), bb.avgPrice(), bb.usdDeposit());
        }

        Set<StrategyTicker> sellTickers = accountCandidates.stream()
                .flatMap(candidate -> candidate.orders().stream())
                .filter(order -> order.direction() == SELL)
                .map(PlannedOrder::ticker)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<StrategyTicker, Integer> sellableByTicker = new LinkedHashMap<>();
        for (StrategyTicker ticker : sellTickers) {
            int sellable = registry.require(account.toBrokerRef(), SellableQuantityPort.class)
                    .getSellableQuantity(ticker, account.toBrokerRef())
                    .quantity();
            sellableByTicker.put(ticker, sellable);
        }
        return new AccountQuote(liveBalance, sellableByTicker);
    }

    // candidates는 반드시 단일 계좌 스코프 — 조회+배정을 한 번에 수행한다
    Allocation allocate(List<Candidate> candidates, LocalDate tradeDate) {
        if (candidates.isEmpty()) return new Allocation(List.of(), List.of(), List.of());
        AccountQuote quote = fetchQuote(candidates);

        SellAllocation sellAllocation = allocateSells(candidates, tradeDate, quote);
        BuyAllocation buyAllocation = allocateBuys(candidates, tradeDate, quote);
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
        Account account = sorted.getFirst().candidate().ctx().account();
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

    private BuyAllocation allocateBuys(List<Candidate> candidates, LocalDate tradeDate, AccountQuote quote) {
        List<Candidate> buyCandidates = candidates.stream()
                .map(candidate -> candidate.withOrders(
                        candidate.orders().stream().filter(order -> order.direction() == BUY).toList()))
                .filter(candidate -> !candidate.orders().isEmpty())
                .toList();
        if (buyCandidates.isEmpty()) return new BuyAllocation(List.of(), List.of());

        List<Candidate> sorted = buyCandidates.stream().sorted(buyPriorityComparator()).toList();
        Account account = sorted.getFirst().ctx().account();
        AccountBalance live = quote.liveBalance();
        if (live == null) {
            throw new IllegalStateException("BUY 잔고 선조회 결과 없음: accountId=" + account.id());
        }
        BigDecimal reservedBuy = orderPort.sumPlannedBuyByAccountAndDate(account.id(), tradeDate);
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

        Map<BatchContext, EnumSet<OrderDirection>> approvedDirections = new LinkedHashMap<>();
        Stream.concat(sellApproved.stream(), buyApproved.stream())
                .forEach(candidate -> candidate.orders().forEach(order -> approvedDirections
                        .computeIfAbsent(candidate.ctx(), ignored -> EnumSet.noneOf(OrderDirection.class))
                        .add(order.direction())));

        return Stream.concat(sellApproved.stream(), buyApproved.stream())
                .map(Candidate::ctx)
                .distinct()
                .map(ctx -> {
                    Candidate source = sourceCandidates.get(ctx);
                    return source.withOrders(source.orders().stream()
                            .filter(order -> approvedDirections.get(ctx).contains(order.direction()))
                            .toList());
                })
                .filter(candidate -> !candidate.orders().isEmpty())
                .toList();
    }

    private record SellRequest(Candidate candidate, List<PlannedOrder> orders) {}

    private record SellAllocation(List<Candidate> approved, List<Candidate> rejected) {}

    private record BuyAllocation(List<Candidate> approved, List<Candidate> rejected) {}
}
