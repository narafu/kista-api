package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.order.TradeEvent;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.*;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.model.tradingcycle.TradingCycleHistory;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.ExecuteTradingUseCase;
import com.kista.domain.port.out.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static com.kista.domain.model.order.Order.OrderDirection.BUY;
import static com.kista.domain.model.order.Order.OrderDirection.SELL;
import static java.math.RoundingMode.FLOOR;
import static java.math.RoundingMode.HALF_UP;

@Slf4j
@Service
@RequiredArgsConstructor
class TradingService implements ExecuteTradingUseCase {

    private final MarketCalendarPort marketCalendarPort;        // 미국 시장 개장일 확인 (DB 캐시)
    private final KisPricePort kisPricePort;                   // 현재 주가 조회
    private final KisOrderPort kisOrderPort;                   // 주문 접수
    private final KisExecutionPort kisExecutionPort;           // 당일 체결 내역 조회
    private final NotifyPort notifyPort;                       // 관리자 텔레그램 알림 (오류·휴장·잔고부족)
    private final UserNotificationPort userNotificationPort;   // 사용자별 텔레그램 알림 (매매 결과)
    private final OrderPort orderPort;                         // 계획 주문 저장·조회
    private final RealtimeNotificationPort realtimeNotificationPort; // SSE 실시간 매매 알림
    private final TradingCycleHistoryPort cycleHistoryPort;    // 사이클별 일별 스냅샷 저장
    private final PrivacyTradePort privacyTradePort;
    private final TradingBalanceLoader balanceLoader;          // 잔고 로드 헬퍼
    private final TradingOrderPlanner orderPlanner;            // 전략 계산 + 주문 저장 헬퍼
    private final CycleRotationService cycleRotationService;   // 사이클 종료 후 재등록

    // planAndSaveOrders 결과: 사이클별 잔고·전략 계산 상태
    record CycleState(
            BatchContext ctx,
            AccountBalance balance,
            InfinitePosition position,      // INFINITE만 non-null (신규 계산 시 — pre-existing skip 케이스는 null)
            TradingSnapshot snapshot,       // INFINITE만 non-null (신규 계산 시)
            BigDecimal startPrice,          // INFINITE만 non-null
            PrivacyTradeBase privacyBase    // PRIVACY만 non-null (cycle 재등록 시 최소금액 산정용)
    ) {}

    // KIS 접수 결과: planAndSaveOrders 상태 + 접수된 주문 목록
    record CyclePlacedState(CycleState state, List<Order> mainOrders) {}

    @Override
    public void execute(TradingCycle cycle, Account account, User user) throws InterruptedException {
        executeBatch(List.of(new BatchContext(cycle, account, user)));
    }

    @Override
    public void executeBatch(List<BatchContext> contexts) throws InterruptedException {
        executeBatch(contexts, DstInfo.calculate());
    }

    // package-private: DstInfo 주입으로 단위 테스트에서 sleep 우회
    void executeBatch(List<BatchContext> contexts, DstInfo dst) throws InterruptedException {
        if (contexts.isEmpty()) return;

        LocalDate today = LocalDate.now();

        // 시장 개장 여부 확인 (1회) — 모든 사이클 공통, 가격 조회 전 조기 반환
        if (!isMarketOpen(today)) return;

        // 시작 시점 현재가 일괄 조회
        List<Ticker> cycleTickers = contexts.stream()
                .map(c -> c.cycle().ticker())
                .distinct().toList();
        Map<Ticker, BigDecimal> startPrices = fetchPricesComplete(cycleTickers, contexts.getFirst().account());

        // 기준 매매표 조회 (PRIVACY)
        boolean hasPrivacy = contexts.stream().anyMatch(c -> c.cycle().type() == TradingCycle.Type.PRIVACY);
        PrivacyTradeBase privacyBase = hasPrivacy
                ? privacyTradePort.findTodayTrade(today).orElse(null)
                : null;

        // planAndSaveOrders — 사이클별: 잔고 로드 + PLANNED 주문 생성·저장 (이미 존재하면 skip)
        List<CycleState> states = new ArrayList<>();
        for (BatchContext ctx : contexts) {
            try {
                CycleState state = planAndSaveOrders(ctx, startPrices, privacyBase, today);
                if (state != null) states.add(state);
            } catch (Exception e) {
                log.error("[cycleId={}] planAndSaveOrders 오류: {}", ctx.cycle().id(), e.getMessage(), e);
                notifyPort.notifyError(e);
            }
        }
        if (states.isEmpty()) return;

        // 공통 대기 — 주문 시각까지 (모든 사이클이 공유하는 단 1회)
        waitForOrderTime(dst);

        // KIS 접수 — 사이클별: BUY 가격 보정 후 PLANNED → KIS 접수
        List<CyclePlacedState> placedStates = new ArrayList<>();
        for (CycleState state : states) {
            try {
                List<Order> mainOrders = executePlannedOrders(today, state);
                placedStates.add(new CyclePlacedState(state, mainOrders));
            } catch (Exception e) {
                log.error("[cycleId={}] KIS 접수 오류: {}", state.ctx().cycle().id(), e.getMessage(), e);
                notifyPort.notifyError(e);
            }
        }

        // 공통 대기 — PostClose까지 (모든 사이클이 공유하는 단 1회)
        waitForPostClose(dst);

        // 장 마감 후 종가 일괄 조회
        Map<Ticker, BigDecimal> closingPrices = fetchPricesComplete(cycleTickers, contexts.getFirst().account());

        // recordAndNotifyExecutions — 사이클별: 체결 조회 + 이력 저장 + 알림
        for (CyclePlacedState ps : placedStates) {
            try {
                recordAndNotifyExecutions(ps, closingPrices, today);
            } catch (Exception e) {
                log.error("[cycleId={}] recordAndNotifyExecutions 오류: {}", ps.state().ctx().cycle().id(), e.getMessage(), e);
                notifyPort.notifyError(e);
            }
        }
    }

    // planAndSaveOrders: 잔고 로드 + PLANNED 주문 생성·저장
    // 오늘 PLANNED 또는 PLACED가 이미 있으면 재계산 없이 그대로 반환 (수동 선행 주문 보존)
    CycleState planAndSaveOrders(BatchContext ctx, Map<Ticker, BigDecimal> startPrices,
                               PrivacyTradeBase privacyBase, LocalDate today) {
        TradingCycle cycle = ctx.cycle();
        Account account = ctx.account();

        // 1. 잔고 로드
        AccountBalance balance = balanceLoader.loadBalanceOrThrow(cycle).balance();
        log.info("잔고 조회 (이력): [{}] {} {}주, 통합주문가능금액 ${}", account.nickname(), cycle.ticker().name(), balance.holdings(), balance.usdDeposit());

        // 2. 전략별 PLANNED 주문 생성·저장
        return switch (cycle.type()) {
            case INFINITE -> {
                BigDecimal price = startPrices.get(cycle.ticker());

                // 오늘 PLANNED·PLACED가 이미 있으면 재계산 skip (수동 주문 우선 보존)
                List<Order> todayOrders = orderPort.findPlannedOrPlacedByAccountAndDate(account.id(), today);
                if (!todayOrders.isEmpty()) {
                    log.info("[{}] 오늘 주문 {}건 존재 — 재계산 skip (수동 선행 또는 중복 호출)", account.nickname(), todayOrders.size());
                    yield new CycleState(ctx, balance, null, null, price, null);
                }

                TradingOrderPlanner.InfiniteCalc calc = orderPlanner.calcInfinite(balance, cycle, price, today, account.nickname());
                List<Order> orders = calc.orders();
                // 주문 유효성: 매수금액 > 잔액 or 매도수량 > 보유수량이면 skip
                BigDecimal totalBuyAmount = orders.stream()
                        .filter(o -> o.direction() == BUY)
                        .map(o -> o.price().multiply(BigDecimal.valueOf(o.quantity())))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                int totalSellQuantity = orders.stream()
                        .filter(o -> o.direction() == SELL)
                        .mapToInt(Order::quantity).sum();
                if (totalBuyAmount.compareTo(balance.usdDeposit()) > 0 || totalSellQuantity > balance.holdings()) {
                    log.warn("[{}] 주문 유효성 실패 — 매수금액 ${} > 잔액 ${} 또는 매도수량 {} > 보유수량 {}",
                            account.nickname(), totalBuyAmount, balance.usdDeposit(), totalSellQuantity, balance.holdings());
                    notifyPort.notifyInsufficientBalance(account, balance, cycle.ticker());
                    yield null;
                }

                orderPlanner.savePlannedOrders(orders, account);
                yield new CycleState(ctx, balance, calc.position(), calc.position().toSnapshot(), price, null);
            }

            case PRIVACY -> {
                if (privacyBase == null) {
                    log.warn("[PRIVACY] 기준 매매표 미수신 — 매매 건너뜀: [{}]", account.nickname());
                    yield null;
                }
                List<Order> privacyOrders = orderPlanner.calcPrivacy(balance, cycle.initialUsdDeposit(), privacyBase);
                orderPlanner.savePlannedOrders(privacyOrders, account);
                yield new CycleState(ctx, balance, null, null, null, privacyBase);
            }
        };
    }

    // recordAndNotifyExecutions: 체결 조회 + 이력 저장 + 알림
    void recordAndNotifyExecutions(CyclePlacedState ps, Map<Ticker, BigDecimal> closingPrices, LocalDate today) {
        CycleState state = ps.state();
        TradingCycle cycle = state.ctx().cycle();
        Account account = state.ctx().account();
        User user = state.ctx().user();

        List<Execution> executions = kisExecutionPort.getExecutions(today, today, cycle.ticker(), account);
        log.info("[{}] 체결 내역 {}건 조회", account.nickname(), executions.size());

        BigDecimal closingPrice = closingPrices.get(cycle.ticker());
        saveAndNotify(state.balance(), closingPrice, state.snapshot(), today,
                ps.mainOrders(), executions, user, account, cycle, state.privacyBase());
    }

    // package-private: DstInfo 주입으로 단위 테스트에서 sleep 우회 (단건 경로)
    void execute(TradingCycle cycle, Account account, User user, DstInfo dst) throws InterruptedException {
        executeBatch(List.of(new BatchContext(cycle, account, user)), dst);
    }

    Map<Ticker, BigDecimal> fetchPricesComplete(List<Ticker> tickers, Account account) {
        Map<Ticker, BigDecimal> prices;
        try {
            prices = new HashMap<>(kisPricePort.getPrices(tickers, account));
        } catch (Exception e) {
            log.warn("복수종목 현재가 일괄 조회 실패, 단건 fallback 사용: {}", e.getMessage());
            prices = new HashMap<>();
        }
        // 배치 응답 누락 ticker → 단건으로 보완
        for (Ticker ticker : tickers) {
            if (!prices.containsKey(ticker)) {
                try {
                    prices.put(ticker, kisPricePort.getPrice(ticker, account));
                } catch (Exception e) {
                    log.warn("[{}] 단건 현재가 조회 실패: {}", ticker.name(), e.getMessage());
                }
            }
        }
        return prices;
    }

    // KIS 접수 전 BUY 가격 보정 + PLANNED 일괄 접수
    // position이 있고 currentPrice가 있을 때만 보정 (수동 선행 주문은 그대로 접수)
    List<Order> executePlannedOrders(LocalDate today, CycleState state) {
        TradingCycle cycle = state.ctx().cycle();
        Account account = state.ctx().account();
        BigDecimal currentPrice = state.startPrice();

        if (currentPrice != null && state.position() != null) {
            capBuyOrders(today, cycle, account, currentPrice, state.position());
        }
        List<Order> planned = orderPort.findPlannedByAccountAndDate(account.id(), today);
        List<Order> placed = planned.stream().map(p -> {
            Order placedOrder = kisOrderPort.place(p, account);
            orderPort.markPlaced(p.id(), placedOrder.kisOrderId()); // 접수 완료 즉시 기록
            // KIS 응답 Order는 id=null — DB PK(p.id())를 살려서 반환 (취소 API에서 사용)
            return new Order(p.id(), p.accountId(), p.tradeDate(), p.ticker(),
                    p.orderType(), p.direction(), p.quantity(), p.price(),
                    Order.OrderStatus.PLACED, placedOrder.kisOrderId());
        }).toList();
        log.info("[{}] 주문 {}건 접수", account.nickname(), placed.size());
        return placed;
    }

    // BUY PLANNED 가격이 currentPrice × 1.10 초과 시 — 전략 공식 기반으로 가격 캡 적용 후 재저장
    // K = position.unitAmount() (실값, 근사 아님), r = ticker.getTargetProfitRate()
    // min(원래가격, cap)으로 평단가·기준가를 각각 캡핑 → 공식(K/2/A, (K-A·q1)·(1+r)/G)으로 수량 재산정
    // 동일 가격으로 캡되면 단일 주문으로 병합
    private void capBuyOrders(LocalDate today, TradingCycle cycle, Account account,
                              BigDecimal currentPrice, InfinitePosition position) {
        List<Order> buyOrders = orderPort.findPlannedByAccountAndDate(account.id(), today)
                .stream().filter(o -> o.direction() == BUY).toList();
        if (buyOrders.isEmpty()) return;

        BigDecimal cap = currentPrice.multiply(new BigDecimal("1.10")).setScale(2, HALF_UP);
        boolean needsCap = buyOrders.stream().anyMatch(o -> o.price().compareTo(cap) > 0);
        if (!needsCap) return;

        log.info("[{}] BUY 가격 보정 필요 — cap={}, 원래 주문: {}", account.nickname(), cap,
                buyOrders.stream().map(o -> o.price() + "×" + o.quantity()).toList());

        BigDecimal k = position.unitAmount();                            // 단위금액 (실값)
        BigDecimal targetProfitRate = cycle.ticker().getTargetProfitRate();

        // 원래 BUY 주문의 가격 순서: buy①=averagePrice(또는 currentPrice), buy②=referencePrice
        // 주문이 1건이면 단순 캡 적용, 2건이면 각각 캡
        List<Order> newBuys;
        if (buyOrders.size() == 1) {
            // 후반 단일 LOC 매수 케이스
            Order orig = buyOrders.getFirst();
            BigDecimal cappedPrice = orig.price().min(cap);
            int qty = k.divide(cappedPrice, 0, FLOOR).intValue();
            if (qty <= 0) {
                log.warn("[{}] 보정 후 BUY 수량 0 — 매수 주문 제외", account.nickname());
                orderPort.deletePlannedBuyByAccountAndDate(account.id(), today);
                return;
            }
            newBuys = List.of(new Order(null, null, today, cycle.ticker(),
                    orig.orderType(), BUY, qty, cappedPrice, Order.OrderStatus.PLANNED, null));
        } else {
            // 전반 2건: buy①(averagePrice 기반), buy②(referencePrice 기반)
            Order buy1 = buyOrders.get(0);
            Order buy2 = buyOrders.get(1);
            BigDecimal cappedAvg = buy1.price().min(cap);
            BigDecimal cappedRef = buy2.price().min(cap);

            int qty1 = k.divide(BigDecimal.valueOf(2), 2, HALF_UP)
                    .divide(cappedAvg, 0, FLOOR).intValue();
            BigDecimal remaining = k.subtract(cappedAvg.multiply(BigDecimal.valueOf(qty1)))
                    .multiply(BigDecimal.ONE.add(targetProfitRate));
            int qty2 = remaining.divide(cappedRef, 0, FLOOR).intValue();

            newBuys = new ArrayList<>();
            if (qty1 > 0) {
                // cappedAvg == cappedRef이면 병합
                if (cappedAvg.compareTo(cappedRef) == 0) {
                    int merged = qty1 + (qty2 > 0 ? qty2 : 0);
                    newBuys.add(new Order(null, null, today, cycle.ticker(),
                            buy1.orderType(), BUY, merged, cappedAvg, Order.OrderStatus.PLANNED, null));
                } else {
                    newBuys.add(new Order(null, null, today, cycle.ticker(),
                            buy1.orderType(), BUY, qty1, cappedAvg, Order.OrderStatus.PLANNED, null));
                    if (qty2 > 0) {
                        newBuys.add(new Order(null, null, today, cycle.ticker(),
                                buy2.orderType(), BUY, qty2, cappedRef, Order.OrderStatus.PLANNED, null));
                    }
                }
            } else if (qty2 > 0) {
                newBuys.add(new Order(null, null, today, cycle.ticker(),
                        buy2.orderType(), BUY, qty2, cappedRef, Order.OrderStatus.PLANNED, null));
            }
        }

        // 기존 BUY PLANNED 삭제 → 보정된 BUY 재저장
        orderPort.deletePlannedBuyByAccountAndDate(account.id(), today);
        if (newBuys.isEmpty()) {
            log.warn("[{}] 보정 후 BUY 주문 없음 — 매수 제외", account.nickname());
            return;
        }
        orderPlanner.savePlannedOrders(newBuys, account);
        log.info("[{}] BUY 가격 보정 완료 — 보정 주문: {}", account.nickname(),
                newBuys.stream().map(o -> o.price() + "×" + o.quantity()).toList());
    }

    private void waitForOrderTime(DstInfo dst) throws InterruptedException {
        long ms = dst.waitUntilOrderTime().toMillis();
        log.info("DST={}, 주문 시각까지 대기: {}ms", dst.isDst(), ms);
        if (ms > 0) Thread.sleep(ms);
        log.info("주문 시각 도달");
    }

    // false 반환 시 알림 발송 후 planAndSaveOrders에서 null 반환 (해당 사이클 skip)
    private boolean isMarketOpen(LocalDate today) {
        boolean open = marketCalendarPort.isMarketOpen(today);
        log.info("시장 개장 여부: {}", open);
        if (!open) {
            log.info("휴장일 — 매매 건너뜀");
            notifyPort.notifyMarketClosed();
        }
        return open;
    }

    void waitForPostClose(DstInfo dst) throws InterruptedException {
        long ms = dst.waitUntilPostClose().toMillis();
        log.info("PostClose까지 대기: {}ms", ms);
        if (ms > 0) Thread.sleep(ms);
        log.info("PostClose 대기 완료");
    }

    private void saveAndNotify(AccountBalance balance, BigDecimal price, TradingSnapshot snapshot,
                               LocalDate today, List<Order> mainOrders,
                               List<Execution> executions, User user, Account account, TradingCycle cycle,
                               PrivacyTradeBase privacyTradeBase) {
        // 체결 결과로 매매 후 잔고 계산 (체결 없으면 pre-trade 그대로)
        AccountBalance postBalance = balance.applyExecutions(executions);
        saveCycleHistory(postBalance, cycle, account, user, price, privacyTradeBase); // 사이클별 스냅샷 저장
        if (snapshot != null) { // PRIVACY 전략은 스냅샷 없음 — 텔레그램 리포트 생략
            // 매매 후 상태로 스냅샷 재계산 (종가 기준 편차율·목표가 포함)
            TradingSnapshot postSnapshot = price != null
                    ? new InfinitePosition(postBalance, cycle.ticker(), price).toSnapshot()
                    : snapshot;
            TradingReport report = buildReport(today, postSnapshot, mainOrders, executions);
            userNotificationPort.notifyTradingReport(user, account, report);
            log.info("[{}] 텔레그램 리포트 발송 완료", account.nickname());
        }
        // 체결 건별 SSE 실시간 알림 (@Transactional 외부에서 호출 — 이미 DB 저장 완료 후)
        for (Execution e : executions) {
            TradeEvent event = e.direction() == SELL
                    ? TradeEvent.sell(e.ticker().name(), e.quantity(), e.price().doubleValue(), e.amountUsd().doubleValue(), account.nickname())
                    : TradeEvent.buy(e.ticker().name(), e.quantity(), e.price().doubleValue(), e.amountUsd().doubleValue(), account.nickname());
            realtimeNotificationPort.notifyTrade(user.id(), event);
        }
        log.info("[{}] SSE 매매 알림 {}건 발송 완료", account.nickname(), executions.size());
    }

    // execute() 종료 시 1건 적재, 사이클 종료 시 연속 정책 처리
    private void saveCycleHistory(AccountBalance balance, TradingCycle cycle,
                                   Account account, User user, BigDecimal price, PrivacyTradeBase privacyTradeBase) {
        TradingCycleHistory history = new TradingCycleHistory(
                null, cycle.id(),
                balance.usdDeposit(), price,          // closingPrice (초기 등록 시 null)
                balance.avgPrice(), balance.holdings(), null
        );
        cycleHistoryPort.save(history);
        log.info("[cycleId={}] 거래 사이클 이력 저장 완료", cycle.id());

        if (history.holdings() == 0 && cycle.cycleSeedType().isConsecutive()) {
            log.info("[cycleId={}] 사이클 종료 — 연속 정책 실행: {}", cycle.id(), cycle.cycleSeedType());
            cycleRotationService.rotate(cycle, account, user, price, privacyTradeBase);
        } else if (history.holdings() == 0) {
            log.info("[cycleId={}] 사이클 종료 (연속 없음)", cycle.id());
        }
    }

    private TradingReport buildReport(LocalDate today, TradingSnapshot snapshot,
                                      List<Order> mainOrders, List<Execution> executions) {
        BigDecimal totalBought = executions.stream()
                .filter(e -> e.direction() == BUY)
                .map(Execution::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSold = executions.stream()
                .filter(e -> e.direction() == SELL)
                .map(Execution::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TradingReport(today, snapshot, mainOrders, totalBought, totalSold);
    }
}
