package com.kista.application.service.trading;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.common.CycleLookups;
import com.kista.common.TimeZones;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.*;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.user.NotificationType;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.UserSettings;
import com.kista.domain.port.out.*;
import com.kista.domain.port.out.broker.LiveBalancePort;
import com.kista.domain.strategy.CycleOrderStrategy;
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
    private final BrokerAdapterRegistry registry;             // live 잔고 검사 전용 (주문 저장 직전 유효성 확인)
    private final CycleOrderComputer orderComputer;            // 전략 계산 + 주문 유효성 검증 공통부
    private final TradingOrderPlanner orderPlanner;            // PLANNED 주문 저장 헬퍼
    private final TradingPriceFetcher priceFetcher;            // 가격 일괄 조회 + 단건 fallback
    private final TradingOrderExecutor orderExecutor;          // BUY 가격 보정 + 증권사 접수
    private final TradingReporter reporter;                    // 체결 조회 + 이력 저장 + 알림
    private final UserPort userPort;                           // ACTIVE 사용자 전체 조회 (장 알림용)
    private final UserSettingsPort userSettingsPort; // MARKET_ALERT 활성 여부 조회

    // planAndSaveOrders 결과: 전략별 잔고·전략 계산 상태
    private record CycleState(
            BatchContext ctx,
            AccountBalance balance,
            InfinitePosition position,      // INFINITE만 non-null (신규 계산 시 — pre-existing skip 케이스는 null)
            BigDecimal startPrice,          // 공통 — INFINITE: BuyOrderPriceCapper / PRIVACY: capPrivacyIfNeeded
            PrivacyTradeBase privacyBase    // PRIVACY만 non-null (rotation 시 최소금액 산정용)
    ) {}

    // 증권사 접수 결과: planAndSaveOrders 상태 + 접수된 주문 목록
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

        // 증권사 접수 — 전략별: BUY 가격 보정 후 PLANNED → 증권사 접수
        List<CyclePlacedState> placedStates = placeAll(states, today);

        // 공통 대기 — 마감 시각까지 (모든 전략이 공유하는 단 1회)
        waitFor("마감 시각", dst.waitUntilPostClose(), dst);
        notifyMarketEvent(NotificationType.MARKET_ALERT, user -> userNotificationPort.notifyMarketClose(user));

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

    // 전략별: BUY 가격 보정 후 PLANNED → 증권사 접수 (실패 사이클은 격리)
    private List<CyclePlacedState> placeAll(List<CycleState> states, LocalDate today) throws InterruptedException {
        List<CyclePlacedState> placedStates = new ArrayList<>();
        for (CycleState state : states) {
            runSafely("증권사 접수", state.ctx(), () -> {
                List<Order> mainOrders = orderExecutor.placeOrders(today,
                        state.ctx().account(), state.ctx().currentCycle().id(),
                        state.startPrice(), state.position());
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
        BigDecimal prevClosePrice = PriceSnapshot.prevCloseOrNull(priceSnapshot);
        List<Order> todayOrders = orderPort.findPlannedOrPlacedByCycleAndDate(currentCycle.id(), today);
        if (!todayOrders.isEmpty()) {
            return buildCycleStateFromExistingOrders(ctx, balance, price, privacyBase, today, prevClosePrice, todayOrders.size());
        }

        // 3. 전략 계산 → live 잔고 검증 → PLANNED 저장 (skip·부족 시 null)
        CycleOrderStrategy.OrderPlan plan = computeValidateAndSave(ctx, balance, prevClosePrice, today, privacyBase, price)
                .orElse(null);
        if (plan == null) return null;

        // CycleState: INFINITE는 position, PRIVACY는 privacyBase — startPrice는 공통(둘 다 캡에 필요)
        BigDecimal startPrice = price; // INFINITE: BuyOrderPriceCapper, PRIVACY: capPrivacyIfNeeded
        PrivacyTradeBase privacyBaseForState = strategy.isPrivacy() ? privacyBase : null;
        return new CycleState(ctx, balance, plan.position(), startPrice, privacyBaseForState);
    }

    // 전략 계산 → live 잔고 검증 → PLANNED 저장 — 마감·개장 스케쥴러 공통 골격
    // empty = 전략 차원 skip(PRIVACY 기준 미수신 등) 또는 live 잔고 부족(사용자 알림 발송됨)
    private Optional<CycleOrderStrategy.OrderPlan> computeValidateAndSave(BatchContext ctx, AccountBalance balance,
            BigDecimal prevClosePrice, LocalDate tradeDate, PrivacyTradeBase privacyBase, BigDecimal currentPrice) {
        Account account = ctx.account();
        Optional<CycleOrderStrategy.OrderPlan> planOpt = orderComputer.compute(
                balance, ctx.strategy(), prevClosePrice, tradeDate, ctx.currentCycle(), privacyBase, account.nickname(), currentPrice);
        if (planOpt.isEmpty()) {
            log.info("[{}] 전략 계산 skip (PRIVACY 기준 미수신 등)", account.nickname());
            return Optional.empty();
        }
        // live 잔고 검사 — 부족 시 알림 후 저장 건너뜀
        if (notifyIfInsufficientLiveBalance(account, ctx.user(), ctx.strategy(), planOpt.get().orders())) {
            return Optional.empty();
        }
        orderPlanner.savePlannedOrders(planOpt.get().orders(), account, ctx.currentCycle().id());
        return planOpt;
    }

    // 오늘 PLANNED·PLACED 주문이 이미 있을 때 캡 보정을 위해 position만 재계산 (저장 없음)
    // INFINITE: position 재계산(skip이면 null), PRIVACY: privacyBase만 담아 반환
    private CycleState buildCycleStateFromExistingOrders(BatchContext ctx, AccountBalance balance,
            BigDecimal price, PrivacyTradeBase privacyBase, LocalDate today, BigDecimal prevClosePrice, int existingCount) {
        Strategy strategy = ctx.strategy();
        Account account = ctx.account();
        log.info("[{}] 오늘 주문 {}건 존재 — 재계산 skip", account.nickname(), existingCount);
        if (strategy.isInfinite()) {
            // position 재계산: 저장 없이 매수 보정(BuyOrderPriceCapper)용으로만 사용
            InfinitePosition recalcPos = orderComputer.compute(
                    balance, strategy, prevClosePrice, today, ctx.currentCycle(), null, account.nickname(), price)
                    .map(CycleOrderStrategy.OrderPlan::position).orElse(null);
            return new CycleState(ctx, balance, recalcPos, price, null);
        }
        // PRIVACY: price 전달 — capPrivacyIfNeeded에서 현재가 기반 BUY 가격 캡 적용
        return new CycleState(ctx, balance, null, price, privacyBase);
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
        notifyMarketEvent(NotificationType.MARKET_ALERT, user -> userNotificationPort.notifyMarketOpen(user));

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

        // 수동 '지금 주문' 등으로 당일 주문이 이미 있으면 신규 생성만 skip — AT_OPEN 선접수는 아래에서 항상 시도
        List<Order> existingOrders = orderPort.findPlannedOrPlacedByCycleAndDate(currentCycle.id(), tradeDate);
        if (!existingOrders.isEmpty()) {
            log.info("[{}] 당일 주문 {}건 이미 존재 — order 생성 skip, 매도 선접수만 진행", account.nickname(), existingOrders.size());
        } else {
            // 잔고 로드
            AccountBalance balance = loadBalance(strategy, account);

            PriceSnapshot priceSnapshot = startPriceSnapshots.get(strategy.ticker());
            BigDecimal prevClosePrice = PriceSnapshot.prevCloseOrNull(priceSnapshot);
            BigDecimal currentPrice = priceSnapshot != null ? priceSnapshot.current() : null;

            // 전략 계산 → live 잔고 검증 → PLANNED 저장 (마감 스케쥴러와 공통 골격)
            if (computeValidateAndSave(ctx, balance, prevClosePrice, tradeDate, privacyBase, currentPrice).isEmpty()) return;
        }

        // AT_OPEN 주문만 개장 시 즉시 선접수 (전략 타입과 무관, 기존 주문이든 신규 저장이든 동일 처리)
        List<Order> atOpenOrders = orderPort.findPlannedByCycleAndDate(currentCycle.id(), tradeDate)
                .stream().filter(o -> o.timing() == Order.OrderTiming.AT_OPEN).toList();
        if (atOpenOrders.isEmpty()) {
            log.info("[{}] 개장 선접수할 주문 없음", account.nickname());
            return;
        }
        orderExecutor.placeGiven(atOpenOrders, account);
    }

    // live 잔고 부족 시 사용자 알림 발송 후 true 반환 — 호출부에서 저장 건너뜀 처리
    private boolean notifyIfInsufficientLiveBalance(Account account, User user, Strategy strategy,
                                                    List<Order> orders) {
        AccountBalance live = registry.require(account, LiveBalancePort.class).getLiveBalance(account, strategy.ticker());
        if (!live.isOrderValid(orders)) {
            log.warn("[{}] live 잔고 부족 — 사용자 알림 발송 후 저장 건너뜀 (예수금 or 보유수량)", account.nickname());
            userNotificationPort.notifyInsufficientBalance(user, account, strategy.type(), strategy.ticker());
            return true;
        }
        return false;
    }

    // ACTIVE 사용자 중 해당 NotificationType이 활성화된 사용자에게 알림 발송
    private void notifyMarketEvent(NotificationType type, java.util.function.Consumer<User> notify) {
        userPort.findAllByStatus(User.UserStatus.ACTIVE).forEach(user -> {
            UserSettings settings = userSettingsPort.findOrDefault(user.id());
            if (settings.isNotificationEnabled(type)) {
                try {
                    notify.accept(user);
                } catch (Exception e) {
                    log.warn("[userId={}] 장 알림 발송 실패: {}", user.id(), e.getMessage());
                }
            }
        });
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
