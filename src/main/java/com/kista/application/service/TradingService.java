package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.*;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.ExecuteTradingUseCase;
import com.kista.domain.port.out.*;
import com.kista.domain.port.out.KisPricePort.PriceSnapshot;
import com.kista.domain.strategy.CycleOrderStrategies;
import com.kista.domain.strategy.CycleOrderStrategy;
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
class TradingService implements ExecuteTradingUseCase {

    private final MarketCalendarPort marketCalendarPort;        // 미국 시장 개장일 확인 (DB 캐시)
    private final NotifyPort notifyPort;                       // 관리자 텔레그램 알림 (오류·휴장·잔고부족)
    private final OrderPort orderPort;                         // 계획 주문 저장·조회
    private final PrivacyTradePort privacyTradePort;
    private final TradingBalanceLoader balanceLoader;          // 잔고 로드 헬퍼
    private final CycleOrderStrategies cycleStrategies;        // 사이클 타입별 주문 전략 라우터
    private final TradingOrderPlanner orderPlanner;            // PLANNED 주문 저장 헬퍼
    private final TradingPriceFetcher priceFetcher;            // 가격 일괄 조회 + 단건 fallback
    private final TradingOrderExecutor orderExecutor;          // BUY 가격 보정 + KIS 접수
    private final TradingReporter reporter;                    // 체결 조회 + 이력 저장 + 알림

    // planAndSaveOrders 결과: 사이클별 잔고·전략 계산 상태
    private record CycleState(
            BatchContext ctx,
            AccountBalance balance,
            InfinitePosition position,      // INFINITE만 non-null (신규 계산 시 — pre-existing skip 케이스는 null)
            TradingSnapshot snapshot,       // INFINITE만 non-null (신규 계산 시)
            BigDecimal startPrice,          // INFINITE만 non-null
            PrivacyTradeBase privacyBase    // PRIVACY만 non-null (cycle 재등록 시 최소금액 산정용)
    ) {}

    // KIS 접수 결과: planAndSaveOrders 상태 + 접수된 주문 목록
    private record CyclePlacedState(CycleState state, List<Order> mainOrders) {}

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

        // 시작 시점 현재가 + 전일종가 일괄 조회 (0회차 진입 방향 판단에 모두 필요)
        List<Ticker> cycleTickers = contexts.stream()
                .map(c -> c.cycle().ticker())
                .distinct().toList();
        Account firstAccount = contexts.getFirst().account();
        Map<Ticker, PriceSnapshot> startPriceSnapshots = priceFetcher.fetchPriceSnapshots(cycleTickers, firstAccount);

        // 기준 매매표 조회 (PRIVACY)
        boolean hasPrivacy = contexts.stream().anyMatch(c -> c.cycle().type() == TradingCycle.Type.PRIVACY);
        PrivacyTradeBase privacyBase = hasPrivacy
                ? privacyTradePort.findTodayTrade(today).orElse(null)
                : null;

        // planAndSaveOrders — 사이클별: 잔고 로드 + PLANNED 주문 생성·저장 (이미 존재하면 skip)
        List<CycleState> states = new ArrayList<>();
        for (BatchContext ctx : contexts) {
            runSafely("planAndSaveOrders", ctx.cycle().id(),
                    () -> planAndSaveOrders(ctx, startPriceSnapshots, privacyBase, today))
                    .ifPresent(states::add);
        }
        if (states.isEmpty()) return;

        // 공통 대기 — 주문 시각까지 (모든 사이클이 공유하는 단 1회)
        waitForOrderTime(dst);

        // KIS 접수 — 사이클별: BUY 가격 보정 후 PLANNED → KIS 접수
        List<CyclePlacedState> placedStates = new ArrayList<>();
        for (CycleState state : states) {
            runSafely("KIS 접수", state.ctx().cycle().id(), () -> {
                List<Order> mainOrders = orderExecutor.placeOrders(today,
                        state.ctx().cycle(), state.ctx().account(),
                        state.startPrice(), state.position());
                return new CyclePlacedState(state, mainOrders);
            }).ifPresent(placedStates::add);
        }

        // 공통 대기 — PostClose까지 (모든 사이클이 공유하는 단 1회)
        waitForPostClose(dst);

        // 장 마감 후 종가 일괄 조회
        Map<Ticker, BigDecimal> closingPrices = priceFetcher.fetchPrices(cycleTickers, firstAccount);

        // recordAndNotifyExecutions — 사이클별: 체결 조회 + 이력 저장 + 알림
        for (CyclePlacedState ps : placedStates) {
            CycleState st = ps.state();
            TradingCycle cycle = st.ctx().cycle();
            runSafely("recordAndNotify", cycle.id(), () -> {
                reporter.recordAndNotify(today, cycle, st.ctx().account(), st.ctx().user(),
                        st.balance(), st.snapshot(), closingPrices.get(cycle.ticker()),
                        ps.mainOrders(), st.privacyBase());
                return null;
            });
        }
    }

    // planAndSaveOrders: 잔고 로드 + PLANNED 주문 생성·저장
    // 오늘 PLANNED 또는 PLACED가 이미 있으면 재계산 없이 그대로 반환 (수동 선행 주문 보존)
    private CycleState planAndSaveOrders(BatchContext ctx, Map<Ticker, PriceSnapshot> startPriceSnapshots,
                                         PrivacyTradeBase privacyBase, LocalDate today) {
        TradingCycle cycle = ctx.cycle();
        Account account = ctx.account();

        // 1. 잔고 로드
        AccountBalance balance = balanceLoader.loadBalanceOrThrow(cycle).balance();
        log.info("잔고 조회 (이력): [{}] {} {}주, 통합주문가능금액 ${}", account.nickname(), cycle.ticker().name(), balance.holdings(), balance.usdDeposit());

        // 2. INFINITE 전용 가드: 오늘 PLANNED·PLACED가 이미 있으면 재계산 skip (수동 주문 보존)
        PriceSnapshot priceSnapshot = startPriceSnapshots.get(cycle.ticker());
        BigDecimal price = priceSnapshot != null ? priceSnapshot.current() : null;
        BigDecimal prevClosePrice = priceSnapshot != null ? priceSnapshot.prevClose() : null;
        if (cycle.type() == TradingCycle.Type.INFINITE) {
            List<Order> todayOrders = orderPort.findPlannedOrPlacedByAccountAndDate(account.id(), today);
            if (!todayOrders.isEmpty()) {
                log.info("[{}] 오늘 주문 {}건 존재 — 재계산 skip (수동 선행 또는 중복 호출)", account.nickname(), todayOrders.size());
                return new CycleState(ctx, balance, null, null, price, null);
            }
        }

        // 3. 전략 위임 — 주문 계획 산출 (PRIVACY 기준매매표 미수신 등은 Optional.empty)
        CycleOrderStrategy strategy = cycleStrategies.of(cycle);
        var planOpt = strategy.plan(new CycleOrderStrategy.PlanContext(
                balance, cycle, prevClosePrice, today, privacyBase, account.nickname()));
        if (planOpt.isEmpty()) return null;
        var plan = planOpt.get(); // 한 번 풀어 재사용

        List<Order> orders = plan.orders();
        // 주문 유효성: 매수금액 > 잔액 or 매도수량 > 보유수량이면 skip + 알림
        if (!balance.isOrderValid(orders)) {
            log.warn("[{}] 주문 유효성 실패 — 잔액 부족 또는 보유수량 초과", account.nickname());
            notifyPort.notifyInsufficientBalance(account, balance, cycle.ticker());
            return null;
        }

        orderPlanner.savePlannedOrders(orders, account);

        // CycleState: INFINITE는 position/snapshot/startPrice, PRIVACY는 privacyBase 보존
        InfinitePosition position = plan.position();
        TradingSnapshot snapshot = position != null ? position.toSnapshot() : null;
        BigDecimal startPrice = cycle.type() == TradingCycle.Type.INFINITE ? price : null;
        PrivacyTradeBase privacyBaseForState = cycle.type() == TradingCycle.Type.PRIVACY ? privacyBase : null;
        return new CycleState(ctx, balance, position, snapshot, startPrice, privacyBaseForState);
    }

    // package-private: DstInfo 주입으로 단위 테스트에서 sleep 우회 (단건 경로)
    void execute(TradingCycle cycle, Account account, User user, DstInfo dst) throws InterruptedException {
        executeBatch(List.of(new BatchContext(cycle, account, user)), dst);
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

    // 사이클별 단계 실행 — 예외 발생 시 로그 + 관리자 알림 후 Optional.empty() 반환 (격리 실행)
    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private <T> Optional<T> runSafely(String phase, UUID cycleId, ThrowingSupplier<T> supplier) throws InterruptedException {
        try {
            return Optional.ofNullable(supplier.get());
        } catch (InterruptedException e) {
            throw e; // InterruptedException은 삼키지 않음
        } catch (Exception e) {
            log.error("[cycleId={}] {} 오류: {}", cycleId, phase, e.getMessage(), e);
            notifyPort.notifyError(e);
            return Optional.empty();
        }
    }
}
