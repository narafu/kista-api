package com.kista.trading.application.service;

import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.AccountBalance;
import com.kista.trading.domain.model.BatchContext;
import com.kista.domain.model.strategy.Strategy;
import com.kista.trading.domain.port.out.OrderPort;
import com.kista.broker.domain.model.BrokerBalance;
import com.kista.broker.domain.port.out.LiveBalancePort;
import com.kista.broker.domain.port.out.SellableQuantityPort;
import com.kista.trading.domain.strategy.CycleOrderStrategies;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static com.kista.trading.domain.model.Order.OrderDirection.BUY;
import static com.kista.trading.domain.model.Order.OrderDirection.SELL;

@Slf4j
@Component
@RequiredArgsConstructor
class TradingOrderBudgetAllocator {

    private final BrokerAdapterRegistry registry;              // live 잔고·판매가능수량 조회
    private final OrderPort orderPort;                         // 기존 PLANNED/PLACED 예약분 조회
    private final CycleOrderStrategies cycleOrderStrategies;    // 전략 타입별 예산 배정 우선순위 조회
    private final TradingParallelRunner parallelRunner;         // 계좌별 브로커 선조회 병렬 실행

    // Allocation input for one strategy cycle; orders may include BUY, SELL, or both.
    record Candidate(BatchContext ctx, List<Order> orders) {}

    // Approved contains only approved directions per candidate; rejected lists are direction-specific.
    record Allocation(List<Candidate> approved, List<Candidate> rejectedBuy, List<Candidate> rejectedSell) {}

    // 계좌 단위 브로커 선조회 결과 — 실패는 failure로 보존해 allocate 시점에 원본 예외로 rethrow한다
    // (runSafely 격리·알림 1회 계약 유지)
    record AccountQuote(AccountBalance liveBalance, Map<Strategy.Ticker, Integer> sellableByTicker, Exception failure) {
        static AccountQuote failed(Exception e) { return new AccountQuote(null, Map.of(), e); }
    }

    // 계좌별 선조회 결과 모음 — allocate 시점에 계좌 id로 조회한다
    record LiveQuotes(Map<UUID, AccountQuote> byAccount) {
        AccountQuote require(UUID accountId) {
            AccountQuote quote = byAccount.get(accountId);
            if (quote == null) throw new IllegalStateException("계좌 라이브 선조회 결과 없음: accountId=" + accountId);
            return quote;
        }
    }

    // 계좌별 candidates를 병렬로 선조회한다 — 계좌 내 예산 차감은 순차이지만 브로커 HTTP 호출은 계좌 간 병렬화한다
    LiveQuotes fetchLiveQuotes(List<List<Candidate>> candidatesByAccount) throws InterruptedException {
        List<TradingParallelRunner.Task<Map.Entry<UUID, AccountQuote>>> tasks = candidatesByAccount.stream()
                .filter(accountCandidates -> !accountCandidates.isEmpty())
                .map(accountCandidates -> {
                    UUID accountId = accountCandidates.getFirst().ctx().account().id();
                    return new TradingParallelRunner.Task<Map.Entry<UUID, AccountQuote>>(accountId,
                            () -> Optional.of(Map.entry(accountId, fetchQuoteInline(accountCandidates))));
                })
                .toList();
        Map<UUID, AccountQuote> byAccount = new LinkedHashMap<>();
        parallelRunner.runAll(tasks).forEach(entry -> byAccount.put(entry.getKey(), entry.getValue()));
        return new LiveQuotes(byAccount);
    }

    // 한 계좌 스코프의 브로커 선조회 — 잔고는 BUY 후보 존재 시만, 판매가능수량은 SELL 후보의 종목별로만 조회한다
    // (기존 allocateBuysForAccount/allocateSellsForAccountTicker의 조회 조건과 동일하게 유지)
    private AccountQuote fetchQuoteInline(List<Candidate> accountCandidates) {
        try {
            Account account = accountCandidates.getFirst().ctx().account();

            List<Candidate> buyCandidates = accountCandidates.stream()
                    .map(candidate -> new Candidate(candidate.ctx(),
                            candidate.orders().stream().filter(order -> order.direction() == BUY).toList()))
                    .filter(candidate -> !candidate.orders().isEmpty())
                    .toList();
            AccountBalance liveBalance = null;
            if (!buyCandidates.isEmpty()) {
                Candidate probe = buyCandidates.stream().sorted(buyPriorityComparator()).findFirst().orElseThrow();
                BrokerBalance bb = registry.require(account, LiveBalancePort.class)
                        .getLiveBalance(account, probe.ctx().strategy().ticker());
                liveBalance = new AccountBalance(bb.holdings(), bb.avgPrice(), bb.usdDeposit());
            }

            Set<Strategy.Ticker> sellTickers = accountCandidates.stream()
                    .flatMap(candidate -> candidate.orders().stream())
                    .filter(order -> order.direction() == SELL)
                    .map(Order::ticker)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            Map<Strategy.Ticker, Integer> sellableByTicker = new LinkedHashMap<>();
            for (Strategy.Ticker ticker : sellTickers) {
                int sellable = registry.require(account, SellableQuantityPort.class)
                        .getSellableQuantity(ticker, account)
                        .quantity();
                sellableByTicker.put(ticker, sellable);
            }
            return new AccountQuote(liveBalance, sellableByTicker, null);
        } catch (Exception e) {
            return AccountQuote.failed(e);
        }
    }

    // 원본 예외를 그대로 rethrow — RuntimeException은 그대로, 아니면 IllegalStateException으로 래핑
    private void rethrowIfFailed(AccountQuote quote) {
        if (quote.failure() == null) return;
        if (quote.failure() instanceof RuntimeException re) throw re;
        throw new IllegalStateException("브로커 선조회 실패", quote.failure());
    }

    // 기존 호출부·단위 테스트 호환용 — 내부에서 단일 계좌 선조회 후 3-인자 allocate에 위임한다
    Allocation allocate(List<Candidate> candidates, LocalDate tradeDate) {
        if (candidates.isEmpty()) return new Allocation(List.of(), List.of(), List.of());
        return allocate(candidates, tradeDate, fetchQuoteInline(candidates));
    }

    // 선조회된 quote를 사용하는 배정 — quote는 candidates와 동일 계좌 스코프여야 한다
    Allocation allocate(List<Candidate> candidates, LocalDate tradeDate, AccountQuote quote) {
        if (candidates.isEmpty()) return new Allocation(List.of(), List.of(), List.of());
        rethrowIfFailed(quote);

        SellAllocation sellAllocation = allocateSells(candidates, tradeDate, quote);
        BuyAllocation buyAllocation = allocateBuysByAccount(candidates, tradeDate, quote);
        List<Candidate> approved = mergeApproved(candidates, sellAllocation.approved(), buyAllocation.approved());
        return new Allocation(approved, buyAllocation.rejected(), sellAllocation.rejected());
    }

    private SellAllocation allocateSells(List<Candidate> candidates, LocalDate tradeDate, AccountQuote quote) {
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
                        entry.getKey(), entry.getValue(), tradeDate, quote, approved, rejected));
        return new SellAllocation(approved, rejected);
    }

    private void allocateSellsForAccountTicker(AccountTicker accountTicker, List<SellRequest> requests, LocalDate tradeDate,
                                                AccountQuote quote, List<Candidate> approved, List<Candidate> rejected) {
        List<SellRequest> sorted = requests.stream().sorted(sellPriorityComparator()).toList();
        Account account = sorted.getFirst().candidate().ctx().account();
        Integer sellableQuantityBoxed = quote.sellableByTicker().get(accountTicker.ticker());
        if (sellableQuantityBoxed == null) {
            throw new IllegalStateException("판매가능수량 선조회 결과 없음: accountId=" + accountTicker.accountId()
                    + ", ticker=" + accountTicker.ticker());
        }
        int sellableQuantity = sellableQuantityBoxed;
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

    private BuyAllocation allocateBuysByAccount(List<Candidate> candidates, LocalDate tradeDate, AccountQuote quote) {
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
                .forEach(entry -> allocateBuysForAccount(entry.getValue(), tradeDate, quote, approved, rejected));
        return new BuyAllocation(approved, rejected);
    }

    private void allocateBuysForAccount(List<Candidate> candidates, LocalDate tradeDate, AccountQuote quote,
                                        List<Candidate> approved, List<Candidate> rejected) {
        List<Candidate> sorted = candidates.stream().sorted(buyPriorityComparator()).toList();
        Account account = sorted.getFirst().ctx().account();
        AccountBalance live = quote.liveBalance();
        if (live == null) {
            throw new IllegalStateException("BUY 잔고 선조회 결과 없음: accountId=" + account.id());
        }
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
