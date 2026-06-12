package com.kista.application.service.trading;

import com.kista.common.CycleLookups;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
class TradingService {

    private final MarketCalendarPort marketCalendarPort;        // 미국 시장 개장일 확인 (DB 캐시)
    private final NotifyPort notifyPort;                       // 관리자 텔레그램 알림 (오류·휴장·잔고부족)
    private final OrderPort orderPort;                         // 계획 주문 저장·조회
    private final PrivacyTradePort privacyTradePort;
    private final StrategyCyclePort strategyCyclePort;         // 현재 StrategyCycle 조회
    private final TradingBalanceLoader balanceLoader;          // 잔고 로드 헬퍼
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
            TradingSnapshot snapshot,       // INFINITE만 non-null (신규 계산 시)
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

        LocalDate today = LocalDate.now();

        // 시장 개장 여부 확인 (1회) — 모든 전략 공통, 가격 조회 전 조기 반환
        if (!isMarketOpen(today)) return;

        // 시작 시점 현재가 + 전일종가 일괄 조회 (0회차 진입 방향 판단에 모두 필요)
        List<Ticker> cycleTickers = contexts.stream()
                .map(c -> c.strategy().ticker())
                .distinct().toList();
        Account firstAccount = contexts.getFirst().account();
        Map<Ticker, PriceSnapshot> startPriceSnapshots = priceFetcher.fetchPriceSnapshots(cycleTickers, firstAccount);

        // 기준 매매표 조회 (PRIVACY)
        boolean hasPrivacy = contexts.stream().anyMatch(c -> c.strategy().type() == Strategy.Type.PRIVACY);
        PrivacyTradeBase privacyBase = hasPrivacy
                ? privacyTradePort.findTodayTrade(today).orElse(null)
                : null;

        // planAndSaveOrders — 전략별: 잔고 로드 + PLANNED 주문 생성·저장 (이미 존재하면 skip)
        List<CycleState> states = planAll(contexts, startPriceSnapshots, privacyBase, today);
        if (states.isEmpty()) return;

        // 공통 대기 — 주문 시각까지 (모든 전략이 공유하는 단 1회)
        waitForOrderTime(dst);

        // KIS 접수 — 전략별: BUY 가격 보정 후 PLANNED → KIS 접수
        List<CyclePlacedState> placedStates = placeAll(states, today);

        // 공통 대기 — PostClose까지 (모든 전략이 공유하는 단 1회)
        waitForPostClose(dst);

        // 장 마감 후 종가 일괄 조회
        Map<Ticker, BigDecimal> closingPrices = priceFetcher.fetchPrices(cycleTickers, firstAccount);

        // recordAndNotifyExecutions — 전략별: 체결 조회 + 이력 저장 + 알림
        reportAll(placedStates, closingPrices, today);
    }

    // 전략별: 잔고 로드 + PLANNED 주문 생성·저장 (실패 사이클은 격리)
    private List<CycleState> planAll(List<BatchContext> contexts, Map<Ticker, PriceSnapshot> startPriceSnapshots,
                                      PrivacyTradeBase privacyBase, LocalDate today) throws InterruptedException {
        List<CycleState> states = new ArrayList<>();
        for (BatchContext ctx : contexts) {
            runSafely("planAndSaveOrders", ctx.strategy().id(),
                    () -> planAndSaveOrders(ctx, startPriceSnapshots, privacyBase, today))
                    .ifPresent(states::add);
        }
        return states;
    }

    // 전략별: BUY 가격 보정 후 PLANNED → KIS 접수 (실패 사이클은 격리)
    private List<CyclePlacedState> placeAll(List<CycleState> states, LocalDate today) throws InterruptedException {
        List<CyclePlacedState> placedStates = new ArrayList<>();
        for (CycleState state : states) {
            runSafely("KIS 접수", state.ctx().strategy().id(), () -> {
                List<Order> mainOrders = orderExecutor.placeOrders(today,
                        state.ctx().account(), state.ctx().currentCycle().id(),
                        state.startPrice(), state.position());
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
            runSafely("recordAndNotify", strategy.id(), () -> {
                reporter.recordAndNotify(today, strategy, currentCycle, st.ctx().account(), st.ctx().user(),
                        st.balance(), st.snapshot(), closingPrices.get(strategy.ticker()),
                        ps.mainOrders(), st.privacyBase());
                return null;
            });
        }
    }

    // planAndSaveOrders: 잔고 로드 + PLANNED 주문 생성·저장
    // 오늘 PLANNED 또는 PLACED가 이미 있으면 재계산 없이 그대로 반환 (수동 선행 주문 보존)
    private CycleState planAndSaveOrders(BatchContext ctx, Map<Ticker, PriceSnapshot> startPriceSnapshots,
                                         PrivacyTradeBase privacyBase, LocalDate today) {
        Strategy strategy = ctx.strategy();
        StrategyCycle currentCycle = ctx.currentCycle();
        Account account = ctx.account();

        // 1. 잔고 로드
        AccountBalance balance = balanceLoader.loadBalanceOrThrow(strategy).balance();
        log.info("잔고 조회 (이력): [{}] {} {}주, 통합주문가능금액 ${}", account.nickname(), strategy.ticker().name(), balance.holdings(), balance.usdDeposit());

        // 2. INFINITE 전용 가드: 오늘 PLANNED·PLACED가 이미 있으면 재계산 skip (수동 주문 보존)
        PriceSnapshot priceSnapshot = startPriceSnapshots.get(strategy.ticker());
        BigDecimal price = priceSnapshot != null ? priceSnapshot.current() : null;
        BigDecimal prevClosePrice = priceSnapshot != null ? priceSnapshot.prevClose() : null;
        if (strategy.type() == Strategy.Type.INFINITE) {
            List<Order> todayOrders = orderPort.findPlannedOrPlacedByCycleAndDate(currentCycle.id(), today);
            if (!todayOrders.isEmpty()) {
                log.info("[{}] 오늘 주문 {}건 존재 — 재계산 skip (수동 선행 또는 중복 호출)", account.nickname(), todayOrders.size());
                return new CycleState(ctx, balance, null, null, price, null);
            }
        }

        // 3. 전략 위임 — 주문 계획 산출 (PRIVACY 기준매매표 미수신 등은 skip) + 잔고 유효성 검증
        CycleOrderComputer.ComputeResult result = orderComputer.computeIfValid(
                balance, strategy, prevClosePrice, today, currentCycle, privacyBase, account.nickname(), account)
                .orElse(null);
        if (result == null) return null;

        orderPlanner.savePlannedOrders(result.orders(), account, currentCycle.id());

        // CycleState: INFINITE는 position/snapshot/startPrice, PRIVACY는 privacyBase 보존
        InfinitePosition position = result.position();
        TradingSnapshot snapshot = position != null ? position.toSnapshot() : null;
        BigDecimal startPrice = strategy.type() == Strategy.Type.INFINITE ? price : null;
        PrivacyTradeBase privacyBaseForState = strategy.type() == Strategy.Type.PRIVACY ? privacyBase : null;
        return new CycleState(ctx, balance, position, snapshot, startPrice, privacyBaseForState);
    }

    // package-private: DstInfo 주입으로 단위 테스트에서 sleep 우회 (단건 경로)
    void execute(Strategy strategy, Account account, User user, DstInfo dst) throws InterruptedException {
        StrategyCycle currentCycle = CycleLookups.requireLatestCycle(strategyCyclePort, strategy.id());
        executeBatch(List.of(new BatchContext(strategy, currentCycle, account, user)), dst);
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

    private void waitForOrderTime(DstInfo dst) throws InterruptedException {
        long ms = dst.waitUntilOrderTime().toMillis();
        log.info("DST={}, 주문 시각까지 대기: {}ms", dst.isDst(), ms);
        if (ms > 0) Thread.sleep(ms);
        log.info("주문 시각 도달");
    }

    private void waitForPostClose(DstInfo dst) throws InterruptedException {
        long ms = dst.waitUntilPostClose().toMillis();
        log.info("PostClose까지 대기: {}ms", ms);
        if (ms > 0) Thread.sleep(ms);
        log.info("PostClose 대기 완료");
    }

    // 전략별 단계 실행 — 예외 발생 시 로그 + 관리자 알림 후 Optional.empty() 반환 (격리 실행)
    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private <T> Optional<T> runSafely(String phase, UUID strategyId, ThrowingSupplier<T> supplier) throws InterruptedException {
        try {
            return Optional.ofNullable(supplier.get());
        } catch (InterruptedException e) {
            throw e; // InterruptedException은 삼키지 않음
        } catch (Exception e) {
            log.error("[strategyId={}] {} 오류: {}", strategyId, phase, e.getMessage(), e);
            notifyPort.notifyError(e);
            return Optional.empty();
        }
    }
}
