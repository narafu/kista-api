package com.kista.application.service.trading;

import com.kista.common.CycleLookups;
import com.kista.common.TimeZones;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.*;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.*;
import com.kista.domain.model.strategy.PriceSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
    private final TradingBalanceLoader balanceLoader;          // 잔고 로드 헬퍼 (KIS: DB 이력 기반)
    private final TosAccountPort tosAccountPort;               // Toss live 잔고 조회 (holdings + usdDeposit)
    private final CycleOrderComputer orderComputer;            // 전략 계산 + 주문 유효성 검증 공통부
    private final TradingOrderPlanner orderPlanner;            // PLANNED 주문 저장 헬퍼
    private final TradingPriceFetcher priceFetcher;            // 가격 일괄 조회 + 단건 fallback
    private final TradingOrderExecutor orderExecutor;          // BUY 가격 보정 + KIS 접수
    private final TradingReporter reporter;                    // 체결 조회 + 이력 저장 + 알림

    // planAndSaveOrders 결과: 전략별 잔고·전략 계산 상태
    private record CycleState(
            BatchContext ctx,
            AccountBalance balance,
            InfinitePosition position,      // INFINITE만 non-null (신규 계산 시 — pre-existing skip 케이스는 null)
            BigDecimal startPrice,          // INFINITE만 non-null
            PrivacyTradeBase privacyBase    // PRIVACY만 non-null (rotation 시 최소금액 산정용)
    ) {}

    // KIS 접수 결과: planAndSaveOrders 상태 + 접수된 주문 목록
    private record CyclePlacedState(CycleState state, List<Order> mainOrders) {}

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

        // 시작 시점 현재가 + 전일종가 일괄 조회 (0회차 진입 방향 판단에 모두 필요)
        List<Ticker> cycleTickers = contexts.stream()
                .map(c -> c.strategy().ticker())
                .distinct().toList();
        Account priceAccount = selectPriceAccount(contexts); // Toss 계좌 우선
        Map<Ticker, PriceSnapshot> startPriceSnapshots = priceFetcher.fetchPriceSnapshots(cycleTickers, priceAccount);

        // 기준 매매표 조회 (PRIVACY)
        boolean hasPrivacy = contexts.stream().anyMatch(c -> c.strategy().isPrivacy());
        PrivacyTradeBase privacyBase = hasPrivacy
                ? privacyTradePort.findTodayTrade(today).orElse(null)
                : null;

        // planAndSaveOrders — 전략별: 잔고 로드 + PLANNED 주문 생성·저장 (이미 존재하면 skip)
        List<CycleState> states = planAll(contexts, startPriceSnapshots, privacyBase, today);
        if (states.isEmpty()) return;

        // 공통 대기 — 주문 시각까지 (모든 전략이 공유하는 단 1회)
        waitFor("주문 시각", dst.waitUntilOrderTime(), dst);

        // KIS 접수 — 전략별: BUY 가격 보정 후 PLANNED → KIS 접수
        List<CyclePlacedState> placedStates = placeAll(states, today);

        // 공통 대기 — PostClose까지 (모든 전략이 공유하는 단 1회)
        waitFor("PostClose", dst.waitUntilPostClose(), dst);

        // 장 마감 후 종가 일괄 조회
        Map<Ticker, BigDecimal> closingPrices = priceFetcher.fetchPrices(cycleTickers, priceAccount);

        // recordAndNotifyExecutions — 전략별: 체결 조회 + 이력 저장 + 알림
        reportAll(placedStates, closingPrices, today);
    }

    // 전략별: 잔고 로드 + PLANNED 주문 생성·저장 (실패 사이클은 격리)
    private List<CycleState> planAll(List<BatchContext> contexts, Map<Ticker, PriceSnapshot> startPriceSnapshots,
                                      PrivacyTradeBase privacyBase, LocalDate today) throws InterruptedException {
        List<CycleState> states = new ArrayList<>();
        for (BatchContext ctx : contexts) {
            runSafely("planAndSaveOrders", ctx,
                    () -> planAndSaveOrders(ctx, startPriceSnapshots, privacyBase, today))
                    .ifPresent(states::add);
        }
        return states;
    }

    // 전략별: BUY 가격 보정 후 PLANNED → KIS 접수 (실패 사이클은 격리)
    private List<CyclePlacedState> placeAll(List<CycleState> states, LocalDate today) throws InterruptedException {
        List<CyclePlacedState> placedStates = new ArrayList<>();
        for (CycleState state : states) {
            runSafely("KIS 접수", state.ctx(), () -> {
                List<Order> mainOrders = orderExecutor.placeOrders(today,
                        state.ctx().account(), state.ctx().currentCycle().id(),
                        state.startPrice(), state.position());
                // 장 개시 스케쥴러에서 AT_OPEN 선접수된 주문도 포함 — markFilledOrders 누락 방지
                List<Order> prePlacedAtOpen = orderPort
                        .findPlacedByCycleAndDate(state.ctx().currentCycle().id(), today)
                        .stream().filter(o -> o.timing() == Order.OrderTiming.AT_OPEN).toList();
                if (!prePlacedAtOpen.isEmpty()) {
                    mainOrders = Stream.concat(prePlacedAtOpen.stream(), mainOrders.stream()).toList();
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
            Strategy strategy = st.ctx().strategy();
            StrategyCycle currentCycle = st.ctx().currentCycle();
            runSafely("recordAndNotify", ps.state().ctx(), () -> {
                reporter.recordAndNotify(today, strategy, currentCycle, st.ctx().account(), st.ctx().user(),
                        st.balance(), closingPrices.get(strategy.ticker()),
                        ps.mainOrders(), st.privacyBase());
                return null;
            });
        }
    }

    // 잔고 로드 — Toss는 /api/v1/holdings live 조회, KIS는 cycle_position DB 이력 사용
    private AccountBalance loadBalance(Strategy strategy, Account account) {
        AccountBalance balance = account.isToss()
                ? tosAccountPort.getBalance(account, strategy.ticker())
                : balanceLoader.loadBalanceOrThrow(strategy).balance();
        log.info("잔고 조회: [{}] {} {}주, 통합주문가능금액 ${}",
                account.nickname(), strategy.ticker().name(), balance.holdings(), balance.usdDeposit());
        return balance;
    }

    // planAndSaveOrders: 잔고 로드 + PLANNED 주문 생성·저장
    // 오늘 PLANNED 또는 PLACED가 이미 있으면 재계산 없이 그대로 반환 (수동 선행 주문 보존)
    private CycleState planAndSaveOrders(BatchContext ctx, Map<Ticker, PriceSnapshot> startPriceSnapshots,
                                         PrivacyTradeBase privacyBase, LocalDate today) {
        Strategy strategy = ctx.strategy();
        StrategyCycle currentCycle = ctx.currentCycle();
        Account account = ctx.account();

        // 1. 잔고 로드
        AccountBalance balance = loadBalance(strategy, account);

        // 2. 오늘 PLANNED·PLACED가 이미 있으면 재계산 skip (장 개시 스케쥴러 선행 또는 수동 주문 보존)
        PriceSnapshot priceSnapshot = startPriceSnapshots.get(strategy.ticker());
        BigDecimal price = priceSnapshot != null ? priceSnapshot.current() : null;
        BigDecimal prevClosePrice = priceSnapshot != null ? priceSnapshot.prevClose() : null;
        List<Order> todayOrders = orderPort.findPlannedOrPlacedByCycleAndDate(currentCycle.id(), today);
        if (!todayOrders.isEmpty()) {
            log.info("[{}] 오늘 주문 {}건 존재 — 재계산 skip", account.nickname(), todayOrders.size());

            if (strategy.isInfinite()) {
                // position 재계산: 저장 없이 매수 보정(BuyOrderPriceCapper)용으로만 사용
                CycleOrderComputer.ComputeResult recalc = orderComputer.compute(
                        balance, strategy, prevClosePrice, today, currentCycle, null, account.nickname());
                InfinitePosition recalcPos = recalc.isSkipped() ? null : recalc.position();
                return new CycleState(ctx, balance, recalcPos, price, null);
            }
            // PRIVACY: privacyBase 보존 (reportAll에서 사이클 회전 등에 필요)
            return new CycleState(ctx, balance, null, null, privacyBase);
        }

        // 3. 전략 위임 — 주문 계획 산출 (PRIVACY 기준매매표 미수신 등은 skip) + 잔고 유효성 검증
        CycleOrderComputer.ComputeResult result = orderComputer.computeUnlessSkipped(
                balance, strategy, prevClosePrice, today, currentCycle, privacyBase, account.nickname())
                .orElse(null);
        if (result == null) return null;

        orderPlanner.savePlannedOrders(result.orders(), account, currentCycle.id());

        // CycleState: INFINITE는 position/startPrice, PRIVACY는 privacyBase 보존
        InfinitePosition position = result.position();
        BigDecimal startPrice = strategy.isInfinite() ? price : null;
        PrivacyTradeBase privacyBaseForState = strategy.isPrivacy() ? privacyBase : null;
        return new CycleState(ctx, balance, position, startPrice, privacyBaseForState);
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

        LocalDate tradeDate = DstInfo.nextTradeDate(); // 장 개시 스케쥴러 전날 저녁 실행 — 내일이 US 거래일
        log.info("개장 order 생성 + INFINITE 매도 선접수 시작 — 거래일 {}", tradeDate);

        if (!isMarketOpen(tradeDate)) return;

        // 가격 스냅샷 일괄 조회 (개장 전 현시점)
        List<Ticker> cycleTickers = contexts.stream().map(c -> c.strategy().ticker()).distinct().toList();
        Account priceAccount = selectPriceAccount(contexts); // Toss 계좌 우선
        Map<Ticker, PriceSnapshot> startPriceSnapshots = priceFetcher.fetchPriceSnapshots(cycleTickers, priceAccount);

        // PRIVACY 기준 매매표 조회 (내일 기준 — FIDA가 미리 송신했을 경우)
        boolean hasPrivacy = contexts.stream().anyMatch(c -> c.strategy().isPrivacy());
        PrivacyTradeBase privacyBase = hasPrivacy
                ? privacyTradePort.findTodayTrade(tradeDate).orElse(null)
                : null;

        // 개장 시각까지 대기
        waitFor("개장 시각", dst.waitUntilMarketOpen(), dst);

        // 전략별: order 생성·저장 + INFINITE 매도 선접수
        for (BatchContext ctx : contexts) {
            runSafely("개장 order+매도접수", ctx, () -> {
                planSaveAndPlaceSells(ctx, startPriceSnapshots, privacyBase, tradeDate);
                return null;
            });
        }

        log.info("개장 order 생성 + INFINITE 매도 선접수 완료");
    }

    // 장 개시 스케쥴러 전용: order 전체 저장 + 예수금 부족 시 사용자 알람 + INFINITE SELL 즉시 접수
    private void planSaveAndPlaceSells(BatchContext ctx, Map<Ticker, PriceSnapshot> startPriceSnapshots,
                                       PrivacyTradeBase privacyBase, LocalDate tradeDate) {
        Strategy strategy = ctx.strategy();
        StrategyCycle currentCycle = ctx.currentCycle();
        Account account = ctx.account();
        User user = ctx.user();

        // 수동 '지금 주문' 등으로 당일 주문이 이미 있으면 중복 생성 skip
        List<Order> existingOrders = orderPort.findPlannedOrPlacedByCycleAndDate(currentCycle.id(), tradeDate);
        if (!existingOrders.isEmpty()) {
            log.info("[{}] 당일 주문 {}건 이미 존재 — 개장 스케쥴러 skip", account.nickname(), existingOrders.size());
            return;
        }

        // 잔고 로드
        AccountBalance balance = loadBalance(strategy, account);

        PriceSnapshot priceSnapshot = startPriceSnapshots.get(strategy.ticker());
        BigDecimal prevClosePrice = priceSnapshot != null ? priceSnapshot.prevClose() : null;

        // 전략 계산 (skip 결과는 건너뜀 — PRIVACY 기준 매매표 없을 시 정상 skip)
        CycleOrderComputer.ComputeResult result = orderComputer.compute(
                balance, strategy, prevClosePrice, tradeDate, currentCycle, privacyBase, account.nickname());
        if (result.isSkipped()) {
            log.info("[{}] 개장 order 계산 skip (PRIVACY 기준 미수신 등)", account.nickname());
            return;
        }

        // 예수금 부족 확인 — 부족해도 저장은 진행 (입금 후 마감 잡에서 실행 가능)
        List<Order> buyOrders = result.orders().stream()
                .filter(o -> o.direction() == Order.OrderDirection.BUY).toList();
        if (!buyOrders.isEmpty() && !balance.isOrderValid(buyOrders)) {
            log.warn("[{}] 예수금 부족 — 사용자 알람 후 주문 저장 진행", account.nickname());
            userNotificationPort.notifyInsufficientBalance(user, account, strategy.type(), strategy.ticker());
        }

        // 전체 PLANNED 저장
        orderPlanner.savePlannedOrders(result.orders(), account, currentCycle.id());

        // AT_OPEN 주문만 개장 시 즉시 선접수 (전략 타입과 무관)
        List<Order> atOpenOrders = orderPort.findPlannedByCycleAndDate(currentCycle.id(), tradeDate)
                .stream().filter(o -> o.timing() == Order.OrderTiming.AT_OPEN).toList();
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
        if (ms > 0) Thread.sleep(ms);
        log.info("{} 도달", label);
    }

    // 가격 조회에 사용할 계좌 선택 — Toss 계좌가 있으면 우선 사용 (토스 시세 API 일관성)
    private Account selectPriceAccount(List<BatchContext> contexts) {
        return contexts.stream()
                .map(BatchContext::account)
                .filter(Account::isToss)
                .findFirst()
                .orElseGet(() -> contexts.getFirst().account());
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
            notifyPort.notifyError(e);
            userNotificationPort.notifyError(ctx.user(), e);
            return Optional.empty();
        }
    }
}
