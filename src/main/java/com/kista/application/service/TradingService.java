package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.Currency;
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
import com.kista.domain.port.in.GetNextOrdersUseCase.SkipReason;
import com.kista.domain.port.out.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static com.kista.domain.model.order.Order.OrderDirection.BUY;
import static com.kista.domain.model.order.Order.OrderDirection.SELL;
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
    private final TradingCyclePort cyclePort;                  // ACTIVE 사이클 조회 + rotate
    private final PrivacyTradePort privacyTradePort;
    private final KisMarginPort kisMarginPort;                 // MAX 재등록 시 USD 잔고 조회
    private final TradingBalanceLoader balanceLoader;          // 잔고 로드 헬퍼
    private final TradingOrderPlanner orderPlanner;            // 전략 계산 + 주문 저장 헬퍼

    // planAndSaveOrders 결과: 사이클별 잔고·전략 계산 상태
    record CycleState(
            BatchContext ctx,
            AccountBalance balance,
            InfinitePosition position,      // INFINITE만 non-null
            TradingSnapshot snapshot,       // INFINITE만 non-null
            BigDecimal startPrice,          // INFINITE만 non-null
            PrivacyTradeBase privacyBase,   // PRIVACY만 non-null (cycle 재등록 시 최소금액 산정용)
            boolean isManualCorrection      // 수동 실행 감지 시 true — KIS 접수 단계에서 보정 주문만 접수
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

        // planAndSaveOrders — 사이클별: 휴장 확인 + 잔고 로드 + PLANNED 주문 생성·저장
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

        // KIS 접수 — 사이클별: PLANNED → KIS 접수 (수동 실행 감지 시 보정 주문만)
        List<CyclePlacedState> placedStates = new ArrayList<>();
        for (CycleState state : states) {
            try {
                List<Order> mainOrders = state.isManualCorrection()
                        ? executeCorrectionOrders(today, state)
                        : executePlannedOrders(today, state.ctx().account());
                placedStates.add(new CyclePlacedState(state, mainOrders));
            } catch (Exception e) {
                log.error("[cycleId={}] KIS 접수 오류: {}", state.ctx().cycle().id(), e.getMessage(), e);
                notifyPort.notifyError(e);
            }
        }

        // 수동 보정 모드 사이클 제외 — recordAndNotifyExecutions는 수동 실행 스레드가 담당 (중복 알림 방지)
        List<CyclePlacedState> recordAndNotifyExecutionsStates = placedStates.stream()
                .filter(ps -> !ps.state().isManualCorrection())
                .toList();

        // 공통 대기 — PostClose까지 (모든 사이클이 공유하는 단 1회)
        waitForPostClose(dst);

        // 장 마감 후 종가 일괄 조회
        Map<Ticker, BigDecimal> closingPrices = fetchPricesComplete(cycleTickers, contexts.getFirst().account());

        // recordAndNotifyExecutions — 사이클별: 체결 조회 + 이력 저장 + 알림
        for (CyclePlacedState ps : recordAndNotifyExecutionsStates) {
            try {
                recordAndNotifyExecutions(ps, closingPrices, today);
            } catch (Exception e) {
                log.error("[cycleId={}] recordAndNotifyExecutions 오류: {}", ps.state().ctx().cycle().id(), e.getMessage(), e);
                notifyPort.notifyError(e);
            }
        }
    }

    // planAndSaveOrders: 휴장 확인 + 잔고 로드 + PLANNED 주문 생성·저장
    // null 반환: 휴장이거나 잔고 부족 — 해당 사이클 이후 단계 모두 skip
    CycleState planAndSaveOrders(BatchContext ctx, Map<Ticker, BigDecimal> startPrices,
                               PrivacyTradeBase privacyBase, LocalDate today) {
        TradingCycle cycle = ctx.cycle();
        Account account = ctx.account();

        // 1. 잔고 로드
        TradingBalanceLoader.BalanceLoad load = balanceLoader.loadBalanceOrThrow(cycle);
        if (load.skipReason() == SkipReason.INSUFFICIENT_BALANCE) {
            log.info("잔고 부족 — 매매 건너뜀: [{}]", account.nickname());
            notifyPort.notifyInsufficientBalance(account, load.balance(), cycle.ticker());
            return null;
        }
        AccountBalance balance = load.balance();
        log.info("잔고 조회 (이력): [{}] {} {}주, 통합주문가능금액 ${}", account.nickname(), cycle.ticker().name(), balance.holdings(), balance.usdDeposit());

        // 2. 전략별 PLANNED 주문 생성·저장
        return switch (cycle.type()) {
            case INFINITE -> {
                // 수동 실행 감지: 오늘 PLACED 주문이 있으면 보정 주문 모드로 전환
                List<Order> todayPlaced = orderPort.findPlacedByAccountAndDate(account.id(), today);
                if (!todayPlaced.isEmpty()) {
                    log.info("[{}] 수동 실행 감지 — 보정 주문 모드", account.nickname());
                    yield new CycleState(ctx, balance, null, null, startPrices.get(cycle.ticker()), null, true);
                }
                BigDecimal price = startPrices.get(cycle.ticker());
                if (balance.shouldSkip(price)) {
                    log.info("0회차 단위금액 부족 — 매매 건너뜀: [{}]", account.nickname());
                    notifyPort.notifyInsufficientBalance(account, balance, cycle.ticker());
                    yield null;
                }
                TradingOrderPlanner.InfiniteCalc calc = orderPlanner.calcInfinite(balance, cycle, price, today, account.nickname());
                orderPlanner.savePlannedOrders(calc.orders(), account);
                yield new CycleState(ctx, balance, calc.position(), calc.position().toSnapshot(), price, null, false);
            }
            case PRIVACY -> {
                if (privacyBase == null) {
                    log.warn("[PRIVACY] 기준 매매표 미수신 — 매매 건너뜀: [{}]", account.nickname());
                    yield null;
                }
                List<Order> privacyOrders = orderPlanner.calcPrivacy(balance, cycle.initialUsdDeposit(), privacyBase);
                orderPlanner.savePlannedOrders(privacyOrders, account);
                yield new CycleState(ctx, balance, null, null, null, privacyBase, false);
            }
        };
    }

    // recordAndNotifyExecutions: 체결 조회 + 이력 저장 + 알림
    void recordAndNotifyExecutions(CyclePlacedState ps, Map<Ticker, BigDecimal> closingPrices, LocalDate today) {
        CycleState state = ps.state();
        TradingCycle cycle = state.ctx().cycle();
        Account account = state.ctx().account();
        User user = state.ctx().user();

        // 8. 체결 내역 조회
        List<Execution> executions = kisExecutionPort.getExecutions(today, today, cycle.ticker(), account);
        log.info("[{}] 체결 내역 {}건 조회", account.nickname(), executions.size());

        // 9. 이력 저장 + 알림
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

    // orders에서 PLANNED 조회 후 KIS에 일괄 접수, 완료 즉시 PLACED 기록
    List<Order> executePlannedOrders(LocalDate today, Account account) {
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

    // 수동 실행 보정: 3PM 현재가 기준 이상적 주문 재계산 후 아침 PLACED 주문과 차이만큼 추가 접수
    List<Order> executeCorrectionOrders(LocalDate today, CycleState state) {
        Account account = state.ctx().account();
        TradingCycle cycle = state.ctx().cycle();
        AccountBalance balance = state.balance();

        // 이상적 주문 재계산 — executeBatch 시작 시 조회한 startPrices 재사용 (~30분 전)
        BigDecimal currentPrice = state.startPrice();
        if (currentPrice == null) {
            log.warn("[{}] 보정 주문 생략 — 시작가 없음 (가격 조회 실패)", account.nickname());
            return List.of();
        }
        TradingOrderPlanner.InfiniteCalc idealCalc = orderPlanner.calcInfinite(balance, cycle, currentPrice, today,
                "보정:" + account.nickname());

        // 아침 PLACED 주문 수량 합산
        List<Order> morningPlaced = orderPort.findPlacedByAccountAndDate(account.id(), today);
        int morningBuyQty  = morningPlaced.stream()
                .filter(o -> o.direction() == BUY).mapToInt(Order::quantity).sum();
        int morningSellQty = morningPlaced.stream()
                .filter(o -> o.direction() == SELL).mapToInt(Order::quantity).sum();

        // 이상 수량 vs 아침 PLACED 수량 비교
        int idealBuyQty  = idealCalc.orders().stream()
                .filter(o -> o.direction() == BUY).mapToInt(Order::quantity).sum();
        int idealSellQty = idealCalc.orders().stream()
                .filter(o -> o.direction() == SELL).mapToInt(Order::quantity).sum();

        // 보정 주문 생성
        List<Order> corrections = new ArrayList<>();
        int correctionBuy  = idealBuyQty  - morningBuyQty;
        int correctionSell = idealSellQty - morningSellQty;
        if (correctionBuy > 0)
            corrections.add(new Order(null, account.id(), today, cycle.ticker(),
                    Order.OrderType.LOC, BUY, correctionBuy, currentPrice, Order.OrderStatus.PLANNED, null));
        if (correctionSell > 0)
            corrections.add(new Order(null, account.id(), today, cycle.ticker(),
                    Order.OrderType.LOC, SELL, correctionSell, currentPrice, Order.OrderStatus.PLANNED, null));

        if (corrections.isEmpty()) {
            log.info("[{}] 보정 주문 불필요 — 수량 차이 없음", account.nickname());
            return List.of();
        }
        orderPlanner.savePlannedOrders(corrections, account);
        return executePlannedOrders(today, account);
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
        AccountBalance postBalance = executions.isEmpty() ? balance : calcPostTradeBalance(balance, executions);
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
            rotateCycleIfConsecutive(cycle, account, user, price, privacyTradeBase);
        } else if (history.holdings() == 0) {
            log.info("[cycleId={}] 사이클 종료 (연속 없음)", cycle.id());
        }
    }

    // 사이클 종료 후 cycleSeedType에 따라 initialUsdDeposit 갱신 + 새 이력 생성
    private void rotateCycleIfConsecutive(TradingCycle cycle, Account account, User user,
                                           BigDecimal price, PrivacyTradeBase privacyTradeBase) {
        // 1. nextInitialUsdDeposit 계산
        BigDecimal nextDeposit;
        if (cycle.cycleSeedType() == TradingCycle.CycleSeedType.MAINTAIN) {
            nextDeposit = cycle.initialUsdDeposit();
        } else { // MAX
            List<com.kista.domain.model.kis.MarginItem> margins;
            try {
                margins = kisMarginPort.getMargin(account);
            } catch (Exception e) {
                log.error("[cycleId={}] MAX 재등록 — KIS 잔고 조회 실패: {}", cycle.id(), e.getMessage());
                notifyPort.notifyError(e);
                return;
            }
            nextDeposit = margins.stream()
                    .filter(m -> Currency.USD == m.currency())
                    .findFirst()
                    .map(com.kista.domain.model.kis.MarginItem::purchasableAmount)
                    .orElse(null);
            if (nextDeposit == null) {
                log.warn("[cycleId={}] MAX 재등록 — USD 잔고 행 없음", cycle.id());
                notifyPort.notifyError(new IllegalStateException("MAX 재등록 실패: USD 잔고 없음 cycleId=" + cycle.id()));
                return;
            }
        }

        // 2. 최소금액 가드
        BigDecimal minRequired = resolveMinRequired(cycle, price, privacyTradeBase);
        if (minRequired != null && nextDeposit.compareTo(minRequired) < 0) {
            log.warn("[cycleId={}] 재등록 취소 — 잔고 부족: {} < 최소 {}", cycle.id(), nextDeposit, minRequired);
            notifyPort.notifyInsufficientBalance(account,
                    new AccountBalance(0, null, nextDeposit), cycle.ticker());
            return;
        }

        // 3. cycle 갱신 (initialUsdDeposit만 변경, 동일 ID 유지)
        TradingCycle rotated = new TradingCycle(
                cycle.id(), cycle.accountId(), cycle.type(), TradingCycle.Status.ACTIVE,
                cycle.ticker(), nextDeposit, cycle.cycleSeedType()
        );
        cyclePort.save(rotated);

        // 4. 새 시작점 이력 (holdings=0, avgPrice=null)
        cycleHistoryPort.save(new TradingCycleHistory(
                null, cycle.id(), nextDeposit, price, null, 0, null
        ));
        log.info("[cycleId={}] 사이클 재등록 완료: {} → initialUsdDeposit={}", cycle.id(), cycle.cycleSeedType(), nextDeposit);
        userNotificationPort.notifyStrategyChanged(user, account, rotated, "재등록");
    }

    // 최소금액 기준: INFINITE = 현재가 × 20 × 2 * 1.1, PRIVACY = currentCycleStart / 2
    private BigDecimal resolveMinRequired(TradingCycle cycle, BigDecimal price, PrivacyTradeBase privacyTradeBase) {
        return switch (cycle.type()) {
            case INFINITE -> price != null
                    ? price.multiply(BigDecimal.valueOf(44)).setScale(2, HALF_UP)
                    : null;
            case PRIVACY -> privacyTradeBase != null
                    ? privacyTradeBase.currentCycleStart().divide(BigDecimal.valueOf(2), 2, HALF_UP)
                    : null;
        };
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

    // 체결 목록으로 매매 후 AccountBalance 계산
    // 평단가 = (기존 매입금 + 금일 매수금) ÷ 신규 보유수량 (매도는 평단가 불변)
    private AccountBalance calcPostTradeBalance(AccountBalance pre, List<Execution> executions) {
        int totalBuyQty = executions.stream()
                .filter(e -> e.direction() == BUY).mapToInt(Execution::quantity).sum();
        int totalSellQty = executions.stream()
                .filter(e -> e.direction() == SELL).mapToInt(Execution::quantity).sum();
        BigDecimal totalBuyAmount = executions.stream()
                .filter(e -> e.direction() == BUY).map(Execution::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSellAmount = executions.stream()
                .filter(e -> e.direction() == SELL).map(Execution::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        int newHoldings = pre.holdings() + totalBuyQty - totalSellQty;
        BigDecimal preAmount = (pre.holdings() == 0 || pre.avgPrice() == null)
                ? BigDecimal.ZERO
                : pre.avgPrice().multiply(BigDecimal.valueOf(pre.holdings()));
        BigDecimal newAvgPrice = newHoldings > 0
                ? preAmount.add(totalBuyAmount).divide(BigDecimal.valueOf(newHoldings), 4, HALF_UP)
                : null;
        BigDecimal newUsdDeposit = pre.usdDeposit().subtract(totalBuyAmount).add(totalSellAmount);
        return new AccountBalance(newHoldings, newAvgPrice, newUsdDeposit);
    }
}
