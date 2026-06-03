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
import com.kista.domain.port.in.GetNextOrdersUseCase;
import com.kista.domain.port.in.ManualExecuteTradingUseCase;
import com.kista.domain.port.out.*;
import com.kista.domain.port.out.UserPort;
import com.kista.domain.strategy.InfiniteTradingStrategy;
import com.kista.domain.strategy.PrivacyTradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

import static com.kista.domain.model.order.Order.OrderDirection.BUY;
import static com.kista.domain.model.order.Order.OrderDirection.SELL;
import static java.math.RoundingMode.HALF_UP;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradingService implements ExecuteTradingUseCase, GetNextOrdersUseCase, ManualExecuteTradingUseCase {

    private final MarketCalendarPort marketCalendarPort;        // 미국 시장 개장일 확인 (DB 캐시)
    private final KisPricePort kisPricePort;                   // 현재 주가 조회
    private final KisOrderPort kisOrderPort;                   // 주문 접수
    private final KisExecutionPort kisExecutionPort;           // 당일 체결 내역 조회
    private final InfiniteTradingStrategy infiniteStrategy;    // INFINITE 전략 — 수량·가격 계산
    private final PrivacyTradingStrategy privacyStrategy;      // PRIVACY 전략 — 기준 매매표 적용
    private final NotifyPort notifyPort;                       // 관리자 텔레그램 알림 (오류·휴장·잔고부족)
    private final UserNotificationPort userNotificationPort;   // 사용자별 텔레그램 알림 (매매 결과)
    private final OrderPort orderPort;                         // 계획 주문 저장·조회
    private final RealtimeNotificationPort realtimeNotificationPort; // SSE 실시간 매매 알림
    private final TradingCycleHistoryPort cycleHistoryPort; // 사이클별 일별 스냅샷 저장
    private final AccountPort accountPort;         // preview 소유권 검증
    private final TradingCyclePort cyclePort;      // preview ACTIVE 사이클 조회
    private final PrivacyTradePort privacyTradePort;
    private final KisMarginPort kisMarginPort;                   // MAX 재등록 시 USD 잔고 조회
    private final UserPort userPort;                             // 수동 실행 시 사용자 조회

    // Phase A 결과: 사이클별 잔고·전략 계산 상태
    private record CycleState(
            BatchContext ctx,
            AccountBalance balance,
            InfinitePosition position,      // INFINITE만 non-null
            TradingSnapshot snapshot,       // INFINITE만 non-null
            BigDecimal startPrice,          // INFINITE만 non-null
            PrivacyTradeBase privacyBase,   // PRIVACY만 non-null (cycle 재등록 시 최소금액 산정용)
            boolean isManualCorrection      // 수동 실행 감지 시 true — Phase B에서 보정 주문만 접수
    ) {}

    // Phase B 결과: Phase A 상태 + KIS 접수된 주문 목록
    private record CyclePlacedState(CycleState state, List<Order> mainOrders) {}

    // execute()와 동일한 잔고 출처(TradingCycleHistory) 및 전략 분기(switch)로 미리보기
    // 휴장 여부는 무시하고 항상 강제 계산 — DB 저장 없음
    @Override
    @Transactional(readOnly = true)
    public Result preview(UUID accountId, UUID requesterId) {
        Account account = accountPort.findByIdOrThrow(accountId);
        account.verifyOwnedBy(requesterId);

        TradingCycle cycle = cyclePort.findByAccountId(accountId).stream()
                .filter(c -> c.status() == TradingCycle.Status.ACTIVE)
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("활성 거래 사이클이 없습니다: " + accountId));

        // 스케줄러는 KST 04:00에 실행 — 04:00 이후 미리보기는 내일 매매 기준
        LocalDate today = LocalTime.now().isBefore(LocalTime.of(4, 0))
                ? LocalDate.now()
                : LocalDate.now().plusDays(1);

        // 잔고 로드 (preview 전용 — 이력 없음도 정상 skip으로 처리)
        BalanceLoad load = tryLoadBalance(cycle);
        if (load.isSkip()) {
            return new Result(today, null, List.of(), load.skipReason());
        }
        AccountBalance balance = load.balance();

        return switch (cycle.type()) {
            case INFINITE -> {
                BigDecimal price = kisPricePort.getPrice(cycle.ticker(), account);
                if (balance.shouldSkip(price)) {
                    // position 포함 — 단위금액·현재가 정보를 프론트에 전달하기 위해
                    InfinitePosition position = new InfinitePosition(balance, cycle.ticker(), price);
                    yield new Result(today, position, List.of(), SkipReason.INSUFFICIENT_BALANCE);
                }
                InfiniteCalc calc = calcInfinite(balance, cycle, price, today, "preview:" + accountId);
                yield new Result(today, calc.position(), calc.orders(), null);
            }
            case PRIVACY -> {
                // 스케줄러 phaseA와 동일: 기준매매표 없으면 skip
                PrivacyTradeBase base = privacyTradePort.findTodayTrade(today).orElse(null);
                if (base == null) {
                    yield new Result(today, null, List.of(), SkipReason.NO_PRIVACY_BASE);
                }
                List<Order> orders = calcPrivacy(balance, cycle.initialUsdDeposit(), base);
                yield new Result(today, null, orders, null);
            }
        };
    }

    @Override
    public List<Order> execute(UUID cycleId, UUID requesterId) {
        // 동기 검증: 소유권·타입·상태
        TradingCycle cycle = cyclePort.findByIdOrThrow(cycleId);
        Account account = accountPort.findByIdOrThrow(cycle.accountId());
        account.verifyOwnedBy(requesterId); // SecurityException → 403
        if (cycle.type() != TradingCycle.Type.INFINITE)
            throw new IllegalArgumentException("INFINITE 사이클만 수동 실행 가능합니다");
        if (cycle.status() != TradingCycle.Status.ACTIVE)
            throw new IllegalArgumentException("ACTIVE 상태의 사이클만 수동 실행 가능합니다");

        // 주문 가능 시간대 확인 — BLOCKED(DST 05:00~17:00, 비DST 06:00~18:00)면 즉시 거부
        DstInfo dst = DstInfo.immediate();
        if (dst.currentSession() == DstInfo.MarketSession.BLOCKED) {
            String range = dst.isDst() ? "05:00~17:00" : "06:00~18:00";
            throw new IllegalStateException("주문 불가 시간대입니다 (KST " + range + ")");
        }

        // 스케줄러와 동일 today 계산: KST 04:00 이후면 +1일(= 다음 US 거래일)
        LocalDate today = LocalTime.now().isBefore(LocalTime.of(4, 0))
                ? LocalDate.now()
                : LocalDate.now().plusDays(1);

        // 이중 실행 방지
        if (!orderPort.findPlacedByAccountAndDate(account.id(), today).isEmpty())
            throw new IllegalStateException("오늘 이미 실행된 사이클입니다");
        User user = userPort.findById(account.userId())
                .orElseThrow(() -> new NoSuchElementException("사용자 없음: " + account.userId()));

        // 일반 LOC 직접 접수 (Phase A/B 동기, Phase C 비동기)
        Map<Ticker, BigDecimal> startPrices = fetchPricesComplete(List.of(cycle.ticker()), account);
        CycleState state = phaseA(new BatchContext(cycle, account, user), startPrices, null, today);
        if (state == null) return List.of(); // 휴장 또는 잔고 부족

        // KIS 접수 실패 시 PLANNED 주문 정리 — 재시도 때 중복 누적 방지
        List<Order> placed;
        try {
            placed = state.isManualCorrection()
                    ? executeCorrectionOrders(today, state)
                    : executePlannedOrders(today, account);
        } catch (Exception e) {
            try {
                orderPort.deletePlannedByAccountAndDate(account.id(), today);
            } catch (Exception cleanup) {
                log.warn("PLANNED 주문 정리 실패 (원본 오류: {}): {}", e.getMessage(), cleanup.getMessage());
            }
            throw e;
        }

        CyclePlacedState placedState = new CyclePlacedState(state, placed);
        Thread.startVirtualThread(() -> {
            try {
                waitForPostClose(dst);
                Map<Ticker, BigDecimal> closingPrices = fetchPricesComplete(List.of(cycle.ticker()), account);
                phaseC(placedState, closingPrices, today);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("수동 실행 Phase C 인터럽트: cycleId={}", cycleId);
            } catch (Exception e) {
                log.error("수동 실행 Phase C 오류: cycleId={}", cycleId, e);
                notifyPort.notifyError(e);
            }
        });

        return placed;
    }

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

        // 시작 시점 현재가 일괄 조회 (INFINITE)
        List<Ticker> infiniteTickers = contexts.stream()
                .filter(c -> c.cycle().type() == TradingCycle.Type.INFINITE)
                .map(c -> c.cycle().ticker())
                .distinct().toList();
        Map<Ticker, BigDecimal> startPrices = infiniteTickers.isEmpty()
                ? Map.of() : fetchPricesComplete(infiniteTickers, contexts.getFirst().account());

        // 기준 매매표 조회 (PRIVACY)
        boolean hasPrivacy = contexts.stream().anyMatch(c -> c.cycle().type() == TradingCycle.Type.PRIVACY);
        PrivacyTradeBase privacyBase = hasPrivacy
                ? privacyTradePort.findTodayTrade(today).orElse(null)
                : null;

        // Phase A — 사이클별: 휴장 확인 + 잔고 로드 + PLANNED 주문 생성·저장
        List<CycleState> states = new ArrayList<>();
        for (BatchContext ctx : contexts) {
            try {
                CycleState state = phaseA(ctx, startPrices, privacyBase, today);
                if (state != null) states.add(state);
            } catch (Exception e) {
                log.error("[cycleId={}] Phase A 오류: {}", ctx.cycle().id(), e.getMessage(), e);
                notifyPort.notifyError(e);
            }
        }
        if (states.isEmpty()) return;

        // 공통 대기 — 주문 시각까지 (모든 사이클이 공유하는 단 1회)
        waitForOrderTime(dst);

        // Phase B — 사이클별: PLANNED → KIS 접수 (수동 실행 감지 시 보정 주문만)
        List<CyclePlacedState> placedStates = new ArrayList<>();
        for (CycleState state : states) {
            try {
                List<Order> mainOrders = state.isManualCorrection()
                        ? executeCorrectionOrders(today, state)
                        : executePlannedOrders(today, state.ctx().account());
                placedStates.add(new CyclePlacedState(state, mainOrders));
            } catch (Exception e) {
                log.error("[cycleId={}] Phase B 오류: {}", state.ctx().cycle().id(), e.getMessage(), e);
                notifyPort.notifyError(e);
            }
        }

        // 수동 보정 모드 사이클 제외 — Phase C는 수동 실행 스레드가 담당 (중복 알림 방지)
        List<CyclePlacedState> phaseCStates = placedStates.stream()
                .filter(ps -> !ps.state().isManualCorrection())
                .toList();

        // INFINITE 사이클 없으면 PostClose 대기 생략 — PRIVACY는 체결·보정이 없으므로 바로 이력 저장
        boolean hasInfinite = phaseCStates.stream()
                .anyMatch(ps -> ps.state().ctx().cycle().type() == TradingCycle.Type.INFINITE);
        if (!hasInfinite) {
            for (CyclePlacedState ps : phaseCStates) {
                try {
                    phaseC(ps, Map.of(), today);
                } catch (Exception e) {
                    log.error("[cycleId={}] Phase C 오류(PRIVACY): {}", ps.state().ctx().cycle().id(), e.getMessage(), e);
                    notifyPort.notifyError(e);
                }
            }
            return;
        }

        // 공통 대기 — PostClose까지 (모든 사이클이 공유하는 단 1회)
        waitForPostClose(dst);

        // 장 마감 후 종가 일괄 조회 (INFINITE ticker만, 1회)
        Map<Ticker, BigDecimal> closingPrices = fetchPricesComplete(infiniteTickers, contexts.getFirst().account());

        // Phase C — 사이클별: 체결 조회 + 이력 저장 + 알림
        for (CyclePlacedState ps : phaseCStates) {
            try {
                phaseC(ps, closingPrices, today);
            } catch (Exception e) {
                log.error("[cycleId={}] Phase C 오류: {}", ps.state().ctx().cycle().id(), e.getMessage(), e);
                notifyPort.notifyError(e);
            }
        }
    }

    // Phase A: 휴장 확인 + 잔고 로드 + PLANNED 주문 생성·저장
    // null 반환: 휴장이거나 잔고 부족 — 해당 사이클 이후 단계 모두 skip
    private CycleState phaseA(BatchContext ctx, Map<Ticker, BigDecimal> startPrices,
                               PrivacyTradeBase privacyBase, LocalDate today) {
        TradingCycle cycle = ctx.cycle();
        Account account = ctx.account();

        // 1. 휴장 확인
        if (!isMarketOpen(today)) return null;

        // 2. 잔고 로드
        BalanceLoad load = loadBalanceOrThrow(cycle);
        if (load.skipReason() == SkipReason.INSUFFICIENT_BALANCE) {
            log.info("잔고 부족 — 매매 건너뜀: [{}]", account.nickname());
            notifyPort.notifyInsufficientBalance(account, load.balance(), cycle.ticker());
            return null;
        }
        AccountBalance balance = load.balance();
        log.info("잔고 조회 (이력): [{}] {} {}주, 통합주문가능금액 ${}", account.nickname(), cycle.ticker().name(), balance.holdings(), balance.usdDeposit());

        // 3. 전략별 PLANNED 주문 생성·저장
        return switch (cycle.type()) {
            case INFINITE -> {
                // 수동 실행 감지: 오늘 PLACED 주문이 있으면 보정 주문 모드로 전환
                List<Order> todayPlaced = orderPort.findPlacedByAccountAndDate(account.id(), today);
                if (!todayPlaced.isEmpty()) {
                    log.info("[{}] 수동 실행 감지 — 보정 주문 모드", account.nickname());
                    yield new CycleState(ctx, balance, null, null, null, null, true);
                }
                BigDecimal price = startPrices.get(cycle.ticker());
                if (balance.shouldSkip(price)) {
                    log.info("0회차 단위금액 부족 — 매매 건너뜀: [{}]", account.nickname());
                    notifyPort.notifyInsufficientBalance(account, balance, cycle.ticker());
                    yield null;
                }
                InfiniteCalc calc = calcInfinite(balance, cycle, price, today, account.nickname());
                savePlannedOrders(calc.orders(), account);
                yield new CycleState(ctx, balance, calc.position(), calc.position().toSnapshot(), price, null, false);
            }
            case PRIVACY -> {
                if (privacyBase == null) {
                    log.warn("[PRIVACY] 기준 매매표 미수신 — 매매 건너뜀: [{}]", account.nickname());
                    yield null;
                }
                List<Order> privacyOrders = calcPrivacy(balance, cycle.initialUsdDeposit(), privacyBase);
                savePlannedOrders(privacyOrders, account);
                yield new CycleState(ctx, balance, null, null, null, privacyBase, false);
            }
        };
    }

    // Phase C: 체결 조회(INFINITE) + 이력 저장 + 알림
    private void phaseC(CyclePlacedState ps, Map<Ticker, BigDecimal> closingPrices, LocalDate today) {
        CycleState state = ps.state();
        TradingCycle cycle = state.ctx().cycle();
        Account account = state.ctx().account();
        User user = state.ctx().user();

        List<Execution> executions = List.of();

        if (cycle.type() == TradingCycle.Type.INFINITE) {
            // 8. 체결 내역 조회
            executions = kisExecutionPort.getExecutions(today, today, cycle.ticker(), account);
            log.info("[{}] 체결 내역 {}건 조회", account.nickname(), executions.size());
        }

        // 9. 이력 저장 + 알림
        saveAndNotify(state.balance(), state.startPrice(), state.snapshot(), today,
                ps.mainOrders(), executions, user, account, cycle, state.privacyBase());
    }

    // package-private: DstInfo 주입으로 단위 테스트에서 sleep 우회 (단건 경로)
    void execute(TradingCycle cycle, Account account, User user, DstInfo dst) throws InterruptedException {
        executeBatch(List.of(new BatchContext(cycle, account, user)), dst);
    }

    private Map<Ticker, BigDecimal> fetchPricesComplete(List<Ticker> tickers, Account account) {
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

    // 이미 계산된 templates를 orders에 PLANNED 상태로 저장
    private void savePlannedOrders(List<Order> templates, Account account) {
        List<Order> planned = templates.stream()
                .map(o -> Order.plan(o, account.id()))
                .toList();
        orderPort.saveAll(planned);
        log.info("[{}] 계획 주문 {}건 저장 (PLANNED)", account.nickname(), planned.size());
    }

    // 잔고 로드 결과 — 정상이면 balance non-null, skip이면 skipReason non-null
    private record BalanceLoad(AccountBalance balance, SkipReason skipReason) {
        boolean isSkip() {
            return skipReason != null;
        }
    }

    // 잔고 로드 — preview용: 이력 없음/잔고 부족 모두 skip 결과로 반환
    private BalanceLoad tryLoadBalance(TradingCycle cycle) {
        var latestOpt = cycleHistoryPort.findRecentByCycleId(cycle.id(), 1).stream().findFirst();
        if (latestOpt.isEmpty()) {
            return new BalanceLoad(null, SkipReason.NO_CYCLE_HISTORY);
        }
        TradingCycleHistory latest = latestOpt.get();
        AccountBalance balance = new AccountBalance(latest.holdings(), latest.avgPrice(), latest.usdDeposit());
        if (balance.shouldSkip()) {
            return new BalanceLoad(balance, SkipReason.INSUFFICIENT_BALANCE);
        }
        return new BalanceLoad(balance, null);
    }

    // 잔고 로드 — execute용: 이력 없음은 데이터 무결성 오류 → IllegalStateException
    private BalanceLoad loadBalanceOrThrow(TradingCycle cycle) {
        TradingCycleHistory latest = cycleHistoryPort.findRecentByCycleId(cycle.id(), 1).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("사이클 이력 없음: cycleId=" + cycle.id()));
        AccountBalance balance = new AccountBalance(
                latest.holdings(), latest.avgPrice(), latest.usdDeposit());
        if (balance.shouldSkip()) {
            return new BalanceLoad(balance, SkipReason.INSUFFICIENT_BALANCE);
        }
        return new BalanceLoad(balance, null);
    }

    // INFINITE 전략 계산 결과 묶음
    private record InfiniteCalc(InfinitePosition position, List<Order> orders) {
    }

    // INFINITE 전략 계산 (execute/preview 공통) — holdings==0 && price==null 방어
    private InfiniteCalc calcInfinite(AccountBalance balance, TradingCycle cycle,
                                      BigDecimal price, LocalDate today, String label) {
        if (balance.holdings() == 0 && price == null) {
            throw new IllegalStateException("현재가 조회 실패: " + cycle.ticker().name());
        }
        InfinitePosition position = new InfinitePosition(balance, cycle.ticker(), price);
        List<Order> orders = infiniteStrategy.buildOrders(position, today);
        log.info("[{}] 전략 계산: priceOffsetRate={}, currentRound={}, unitAmount={}, orders={}",
                label, position.priceOffsetRate(), position.currentRound(),
                position.unitAmount(), orders.size());
        return new InfiniteCalc(position, orders);
    }

    // PRIVACY 전략 계산
    private List<Order> calcPrivacy(AccountBalance balance, BigDecimal initialUsdDeposit, PrivacyTradeBase privacyTradeBase) {
        return privacyStrategy.buildOrders(balance, initialUsdDeposit, privacyTradeBase);
    }

    // orders에서 PLANNED 조회 후 KIS에 일괄 접수, 완료 즉시 PLACED 기록
    private List<Order> executePlannedOrders(LocalDate today, Account account) {
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
    private List<Order> executeCorrectionOrders(LocalDate today, CycleState state) {
        Account account = state.ctx().account();
        TradingCycle cycle = state.ctx().cycle();
        AccountBalance balance = state.balance();

        // 3PM 현재가로 이상적 주문 재계산
        BigDecimal currentPrice = kisPricePort.getPrice(cycle.ticker(), account);
        InfiniteCalc idealCalc = calcInfinite(balance, cycle, currentPrice, today,
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
        savePlannedOrders(corrections, account);
        return executePlannedOrders(today, account);
    }

    private void waitForOrderTime(DstInfo dst) throws InterruptedException {
        long ms = dst.waitUntilOrderTime().toMillis();
        log.info("DST={}, 주문 시각까지 대기: {}ms", dst.isDst(), ms);
        if (ms > 0) Thread.sleep(ms);
        log.info("주문 시각 도달");
    }

    // false 반환 시 알림 발송 후 Phase A에서 null 반환 (해당 사이클 skip)
    private boolean isMarketOpen(LocalDate today) {
        boolean open = marketCalendarPort.isMarketOpen(today);
        log.info("시장 개장 여부: {}", open);
        if (!open) {
            log.info("휴장일 — 매매 건너뜀");
            notifyPort.notifyMarketClosed();
        }
        return open;
    }

    private void waitForPostClose(DstInfo dst) throws InterruptedException {
        long ms = dst.waitUntilPostClose().toMillis();
        log.info("PostClose까지 대기: {}ms", ms);
        if (ms > 0) Thread.sleep(ms);
        log.info("PostClose 대기 완료");
    }

    private void saveAndNotify(AccountBalance balance, BigDecimal price, TradingSnapshot snapshot,
                               LocalDate today, List<Order> mainOrders,
                               List<Execution> executions, User user, Account account, TradingCycle cycle,
                               PrivacyTradeBase privacyTradeBase) {
        saveCycleHistory(balance, cycle, account, user, price, privacyTradeBase); // 사이클별 스냅샷 저장
        if (snapshot != null) { // PRIVACY TODO: 스냅샷 미생성 시 텔레그램 리포트 생략
            TradingReport report = buildReport(today, snapshot, mainOrders, executions);
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
                balance.usdDeposit(), price,          // currentPrice (PRIVACY/초기 등록은 null)
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
}
