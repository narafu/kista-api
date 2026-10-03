package com.kista.tradingstats.domain.backtest;

import com.kista.tradingstats.domain.model.backtest.BacktestCommand;
import com.kista.tradingstats.domain.model.backtest.BacktestPoint;
import com.kista.tradingstats.domain.model.DailyCandle;
import com.kista.broker.domain.model.Execution;
import com.kista.matching.domain.model.PlannedOrder;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.OrderDirection;
import com.kista.privacy.domain.model.PrivacyTradeBase;
import com.kista.matching.domain.model.AccountBalance;
import com.kista.matching.domain.model.InfinitePosition;
import com.kista.matching.domain.model.StrategyVrDetail;
import com.kista.matching.domain.model.VrPosition;
import com.kista.matching.domain.strategy.CycleOrderStrategies;
import com.kista.matching.domain.strategy.CycleOrderStrategy;
import com.kista.matching.domain.strategy.InfiniteStrategy;
import com.kista.matching.domain.strategy.PriceCapPolicy;
import com.kista.matching.domain.strategy.VrStrategy;
import com.kista.trading.domain.strategy.VrRampParams;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.kista.sharedkernel.OrderDirection.BUY;
import static java.math.RoundingMode.HALF_UP;
import com.kista.sharedkernel.StrategyCycleSeedType;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyDefaults;

// 백테스트 시뮬레이션 엔진 — 일봉을 하루씩 진행하며 기존 전략 순수 함수를 올바른 순서로 호출한다
// 새 매매 수식은 하나도 만들지 않는다: 주문 생성·V값 갱신·가격 캡·램프는 전부 domain/strategy·domain/model/strategy에 위임
// domain 레이어는 Spring 빈 금지(HexagonalArchitectureTest) — 평범한 생성자 주입
public class BacktestEngine {

    // 캡 재산정(사다리 재생성) 전용 — VrStrategy는 무상태라 인스턴스 공유 가능
    private static final VrStrategy VR_STRATEGY = new VrStrategy();
    // 캡 재산정(수량 재계산 + 보정 주문) 전용 — InfiniteStrategy도 무상태
    private static final InfiniteStrategy INFINITE_STRATEGY = new InfiniteStrategy();
    // 리버스모드 별지점 산출에 쓰는 최근 종가 개수 — 운영 CycleOrderComputer.STAR_POINT_WINDOW와 동일
    private static final int STAR_POINT_WINDOW = 5;

    private final CycleOrderStrategies strategies;

    public BacktestEngine(CycleOrderStrategies strategies) {
        this.strategies = strategies;
    }

    // 엔진 내부 조립 결과 — cagr/mdd 등 지표 계산과 최종 응답 조립은 BacktestService 몫
    public record Output(
            List<BacktestPoint> points, // 일별 자산 곡선
            int tradeCount,             // 체결 건수 누계
            int cycleCount,             // 진행된 사이클 수 (롤오버 포함)
            List<String> warnings       // 시뮬레이션 중 발생한 경고
    ) {}

    // candles는 command.from()~to() 구간이 날짜 오름차순으로 정렬돼 들어온다고 가정한다 (조달은 호출측 책임)
    public Output run(List<DailyCandle> candles, BacktestCommand command) {
        if (candles.isEmpty()) return new Output(List.of(), 0, 0, List.of());
        return switch (command.type()) {
            case VR -> runVr(candles, command);
            case INFINITE -> runInfinite(candles, command);
            // PRIVACY는 기준 매매표가 있어야 주문을 만들 수 있다 — 맵을 받는 3-arg 오버로드를 쓰라는 신호로 명확히 실패시킨다
            default -> throw new IllegalArgumentException("백테스트 미지원 전략: " + command.type());
        };
    }

    // PRIVACY 전용 진입점 — 기준 매매표 조회는 DB I/O라 순수 도메인에서 못 하므로 날짜별 맵을 호출측(BacktestService)이 미리 조달한다
    // 맵에 키가 없는 날짜는 "기준 매매표 미수신"과 동일 취급 (그날 주문 없음)
    public Output run(List<DailyCandle> candles, BacktestCommand command, Map<LocalDate, PrivacyTradeBase> privacyBases) {
        if (command.type() != StrategyType.PRIVACY) return run(candles, command);
        if (candles.isEmpty()) return new Output(List.of(), 0, 0, List.of());
        return runPrivacy(candles, command, privacyBases);
    }

    // --- 전략 공통 일봉 루프 ---

    // 전략별 하루 처리 콜백 — 체결·자산 기록이 끝난 뒤 호출돼 "내일 체결 대상" 주문을 반환한다
    @FunctionalInterface
    private interface DayPlanner {
        // nextSession: 오늘 만든 주문이 체결될 다음 세션 날짜 (마지막 캔들이면 null — 체결 기회가 없다)
        List<PlannedOrder> planFor(DailyCandle candle, LocalDate nextSession);
    }

    // 전략 무관 일봉 루프 — look-ahead 불변조건(어제 주문만 오늘 체결)을 여기 한 곳에서만 관리한다
    // 캔들 D 처리 끝에 만드는 주문은 세션 D+1에 체결된다 — 운영이 세션 S 주문을 S-1 확정 종가로 만드는 것과 같게
    // 주문 기준가(전일종가·캡 기준가)는 오늘(D) 종가를 쓴다(D 종가는 D+1 주문 시점에 이미 확정된 값이라 look-ahead가 아니다)
    private Output runDays(List<DailyCandle> candles, DayState state, List<String> warnings, DayPlanner planner) {
        List<BacktestPoint> points = new ArrayList<>();
        List<PlannedOrder> pending = List.of(); // 어제 생성한 주문 — 오늘 캔들로 체결 판정
        // 매수 예산 거절 연속구간 커서 — VR 보류·PRIVACY 결측요약과 동일 취지로 일별 경고 폭주를 막는다
        LocalDate rejectFrom = null;
        LocalDate rejectTo = null;
        int rejectDays = 0;

        for (int i = 0; i < candles.size(); i++) {
            DailyCandle candle = candles.get(i);
            LocalDate nextSession = i + 1 < candles.size() ? candles.get(i + 1).date() : null;

            // (1) 어제 주문을 오늘 캔들로 체결 — 오늘 만든 주문은 오늘 체결하지 않는다(look-ahead 방지 핵심 불변조건)
            state.applyFills(FillSimulator.simulate(pending, candle));
            // 전략 잔고가 모자라 유휴 현금(MAINTAIN 초과분)까지 쓴 매수면 그만큼 유휴 현금에서 메운다
            // BUY는 예산 검증을 통과한 지정가 이하로만 체결되므로 (전략 잔고 + 유휴 현금)은 음수가 될 수 없다
            state.coverShortfallFromIdle();

            // (2) 오늘 EOD 자산 기록 — 보유분은 평단가가 아닌 종가 시장가로 평가
            points.add(new BacktestPoint(candle.date(),
                    state.availableCash().add(marketValue(candle, state.balance.holdings())),
                    state.principal));

            // (3) 전략별 하루 처리 — 사이클 판정 후 오늘 주문 생성 + 접수 전 BUY 가격 캡 보정
            List<PlannedOrder> planned = planner.planFor(candle, nextSession);

            // (4) 접수 전 BUY 예산 검증 — 운영 TradingOrderBudgetAllocator와 동일: BUY 지정가 합계(보정 주문 포함)가
            // 예수금을 넘으면 그 날 BUY를 전부 거절하고 SELL만 접수한다(마지막 캔들 주문은 체결 기회가 없어 판정 생략)
            if (nextSession != null && AccountBalance.buyTotal(planned).compareTo(state.availableCash()) > 0) {
                planned = planned.stream().filter(o -> o.direction() != BUY).toList();
                if (rejectFrom == null) rejectFrom = nextSession;
                rejectTo = nextSession;
                rejectDays++;
            } else if (rejectFrom != null) {
                warnings.add(buyRejectGapWarning(rejectFrom, rejectTo, rejectDays));
                rejectFrom = null;
                rejectDays = 0;
            }
            pending = planned;
        }
        // 마지막 캔들까지 이어진 거절 구간은 루프 안에서 닫힐 기회가 없다 — 여기서 flush
        if (rejectFrom != null) warnings.add(buyRejectGapWarning(rejectFrom, rejectTo, rejectDays));
        // 마지막 pending은 체결 기회가 없어 자연히 버려진다
        return new Output(List.copyOf(points), state.tradeCount, state.cycleCount, List.copyOf(warnings));
    }

    // 매수 예산 거절 연속구간 1건 요약(체결 세션 날짜 기준) — PrivacyState.flushMissingBaseGap과 동일 포맷 관용구
    private static String buyRejectGapWarning(LocalDate from, LocalDate to, int days) {
        return from + " ~ " + to + "(총 " + days + "일): 매수 주문 합계가 예수금을 넘어 그날 매수 주문을 모두 거절했습니다.";
    }

    // 경고 문구용 달러 금액 표기 — 천 단위 구분 + 소수 2자리
    private static String usd(BigDecimal amount) {
        return String.format(Locale.US, "$%,.2f", amount);
    }

    // --- VR 경로 ---

    private Output runVr(List<DailyCandle> candles, BacktestCommand command) {
        StrategyVrDetail detail = vrDetail(command);
        VrState state = new VrState(command, detail, candles.getFirst());
        List<String> warnings = new ArrayList<>();

        return runDays(candles, state, warnings, (candle, nextSession) -> {
            // 롤오버 판정 — 오늘 체결까지 반영한 잔고 기준으로 판정해야 오늘 새 사이클의 첫 주문이 나온다
            rolloverIfDue(state, command, detail, candle, warnings);
            return planVrOrders(state, command, candle);
        });
    }

    // VR N주 롤오버 — 운영 VrCycleRolloverService와 동일 순서
    // (인출 반영 예수금 음수면 보류 → 조정 전 예수금으로 V′ 계산 → V′≤0이면 보류 → 입출금 반영 후 새 사이클)
    private void rolloverIfDue(VrState state, BacktestCommand command, StrategyVrDetail detail,
                               DailyCandle candle, List<String> warnings) {
        LocalDate dueDate = state.cycleStartDate.plusWeeks(command.vrIntervalWeeks());
        // 평가 기준 캔들 = due일(휴장이면 직전 거래일) — 운영 lastTradingDayOnOrBefore와 동일, 보류 후 재시도도 같은 캔들로 평가한다
        if (!candle.date().isAfter(dueDate)) state.dueEvaluationCandle = candle;
        // due 조건: 사이클 시작일 + intervalWeeks ≤ 오늘 (당일 포함)
        if (candle.date().isBefore(dueDate)) return;

        // 인출 반영 후 예수금이 음수면 V′ 계산 전에 보류 — 운영과 동일(예수금을 0으로 깎아 진행하지 않는다)
        if (state.balance.usdDeposit().add(BigDecimal.valueOf(command.vrRecurringAmount())).signum() < 0) {
            state.warnHold(warnings, candle.date() + ": 인출액이 예수금을 초과해 VR 주기 갱신을 보류했습니다.");
            return;
        }

        // 램프 기준 경과 주수는 전략 최초 사이클 시작일(= 백테스트 시작일) 기준 — 사이클마다 리셋하지 않는다
        long weeks = ChronoUnit.WEEKS.between(state.firstCycleStartDate, candle.date());
        DailyCandle evaluationCandle = state.dueEvaluationCandle;
        BigDecimal evaluation = marketValue(evaluationCandle, state.balance.holdings());
        BigDecimal newValue = VrPosition.nextValue(state.value, state.balance.usdDeposit(),
                detail.gradientAt(weeks), command.vrRecurringAmount(), evaluation);

        // V′≤0이면 롤오버 보류 — cycleStartDate를 갱신하지 않아 다음 거래일에 재판정한다
        if (newValue.signum() <= 0) {
            state.warnHold(warnings, candle.date() + ": 다음 주기 목표 평가금(V)이 0 이하로 계산되어 VR 주기 갱신을 보류했습니다.");
            return;
        }

        state.applyRecurringCashFlow(command.vrRecurringAmount());
        state.value = newValue;
        // 새 사이클 시작일 = 평가 기준일(항상 실제 거래일) — 실행일을 쓰면 휴장 due일마다 N주 스케줄이 누적해서 밀린다
        state.cycleStartDate = evaluationCandle.date();
        // 새 사이클의 poolLimit — 자본 조정까지 반영한 개장 예수금 × 램프 재계산 비율
        state.poolLimit = poolLimitOf(state.balance.usdDeposit(), detail.poolLimitRateAt(weeks));
        state.poolUsed = BigDecimal.ZERO;
        state.cycleCount++;
        state.holdReason = null;
    }

    // 오늘 주문 생성 — PlanContext 조립 후 기존 VrCycleOrderStrategy.plan()에 위임
    private List<PlannedOrder> planVrOrders(VrState state, BacktestCommand command, DailyCandle candle) {
        // referencePrice·currentPrice 모두 다음 세션 기준 전일종가(= 오늘 종가)로 채운다 — 백테스트엔 장중 재조회 현재가가 없다(알려진 근사)
        BigDecimal refClose = candle.close();
        CycleOrderStrategy.PlanContext.VrInputs vrInputs = new CycleOrderStrategy.PlanContext.VrInputs(
                state.value, command.vrBandWidth(), state.poolLimit, state.poolUsed,
                refClose, refClose, command.vrRecurringAmount());
        CycleOrderStrategy.PlanContext ctx = new CycleOrderStrategy.PlanContext(
                state.balance, command.ticker(), candle.date(), "backtest", null, null, vrInputs);

        Optional<CycleOrderStrategy.OrderPlan> plan = strategies.of(StrategyType.VR).plan(ctx);
        List<PlannedOrder> orders = plan.map(CycleOrderStrategy.OrderPlan::orders).orElse(List.of());
        // 캡 재산정에는 plan()이 이미 조립해 실어 보낸 VrPosition을 그대로 재사용한다(운영 BuyOrderPriceCapper와 동일 계약)
        return applyVrBuyCap(orders, refClose,
                plan.map(CycleOrderStrategy.OrderPlan::vrPosition).orElse(null), command.ticker(), candle.date());
    }

    // 접수 전 BUY 가격 캡 보정 — 운영 VrCycleOrderStrategy.capBuyOrders()와 동일 규칙, 현재가 대용으로 전일 종가 사용
    private List<PlannedOrder> applyVrBuyCap(List<PlannedOrder> orders, BigDecimal refClose, VrPosition position,
                                      StrategyTicker ticker, LocalDate tradeDate) {
        if (position == null) return orders;
        List<PlannedOrder> buys = orders.stream().filter(o -> o.direction() == BUY).toList();
        if (buys.isEmpty()) return orders;
        // bootstrap 배치(LOC)는 사다리 공식과 무관한 별도 산정가라 재산정 대상이 아니다 — BuyOrderPriceCapper.isVrBootstrapShaped와 동일 판정
        if (buys.stream().anyMatch(o -> o.orderType() == OrderType.LOC)) return orders;

        BigDecimal cap = PriceCapPolicy.capFor(refClose);
        if (buys.stream().noneMatch(o -> o.price().compareTo(cap) > 0)) return orders;
        return PriceCapPolicy.replaceBuysPreservingOrder(orders, VR_STRATEGY.buildCappedBuyOrders(position, ticker, tradeDate, cap));
    }

    // --- INFINITE 경로 ---

    private Output runInfinite(List<DailyCandle> candles, BacktestCommand command) {
        InfiniteState state = new InfiniteState(command, candles.getFirst().close());
        List<String> warnings = new ArrayList<>();

        return runDays(candles, state, warnings,
                (candle, nextSession) -> planInfiniteDay(state, command, candle, warnings));
    }

    // INFINITE 하루 처리 — 순서 고정: 별지점 윈도우 갱신 → 리버스모드 전이 → 사이클 종료 판정 → 주문 생성
    private List<PlannedOrder> planInfiniteDay(InfiniteState state, BacktestCommand command, DailyCandle candle,
                                               List<String> warnings) {
        if (state.stopped) return List.of(); // 사이클 시드 정책으로 매매 중단된 뒤엔 주문 없음
        // 오늘 종가는 리버스모드 여부와 무관하게 매일 윈도우에 쌓는다(사이클 스코프 — 종료 시 함께 비워짐)
        state.pushClose(candle.close());

        // 리버스모드 전이 — 오늘 체결까지 반영한 잔고와 오늘 종가로 판정(운영 CyclePositionPersistor.computeNewReverseMode와 동일 타이밍)
        state.applyReverseModeTransition(command.ticker(), candle.close());

        // 청산(어제 보유>0 → 오늘 0) 판정은 반드시 주문 생성 전 — 오늘 주문은 새 사이클의 0회차 주문이어야 한다
        if (state.balance.holdings() == 0 && state.prevDayHoldings > 0) {
            if (!state.restartCycle(command.cycleSeedTypeOrMax(), candle.date(), warnings)) return List.of();
            state.resetReverseMode();
        }
        // 주문 생성은 보유수량을 바꾸지 않으므로 여기서 "오늘 종료 시점 보유수량"을 확정해도 안전하다(모든 분기 공통 통과 지점)
        state.prevDayHoldings = state.balance.holdings();

        return planInfiniteOrders(state, command, candle);
    }

    // 오늘 주문 생성 — PlanContext 조립 후 기존 InfiniteCycleOrderStrategy.plan()에 위임
    private List<PlannedOrder> planInfiniteOrders(InfiniteState state, BacktestCommand command, DailyCandle candle) {
        // 다음 세션 기준 전일종가 = 오늘 종가 — 0회차 평단 대용가·캡 기준가 공용
        BigDecimal refClose = candle.close();
        CycleOrderStrategy.PlanContext.InfiniteInputs infiniteInputs = new CycleOrderStrategy.PlanContext.InfiniteInputs(
                state.divisionCount, refClose, state.starPointPrice(), state.reverseMode, state.isFirstReverseDay);
        CycleOrderStrategy.PlanContext ctx = new CycleOrderStrategy.PlanContext(
                state.balance, command.ticker(), candle.date(), "backtest", infiniteInputs, null, null);

        Optional<CycleOrderStrategy.OrderPlan> plan = strategies.of(StrategyType.INFINITE).plan(ctx);
        List<PlannedOrder> orders = plan.map(CycleOrderStrategy.OrderPlan::orders).orElse(List.of());
        // 리버스모드면 position이 null — 운영 BuyOrderPriceCapper와 동일하게 캡 재산정 대상에서 제외된다
        return applyInfiniteBuyCap(orders, refClose,
                plan.map(CycleOrderStrategy.OrderPlan::position).orElse(null), candle.date());
    }

    // 접수 전 BUY 가격 캡 보정 — 운영 InfiniteCycleOrderStrategy.capBuyOrders()와 동일 규칙, 현재가 대용으로 전일 종가 사용
    private List<PlannedOrder> applyInfiniteBuyCap(List<PlannedOrder> orders, BigDecimal refClose, InfinitePosition position,
                                            LocalDate tradeDate) {
        if (position == null) return orders;
        List<PlannedOrder> buys = orders.stream().filter(o -> o.direction() == BUY).toList();
        if (buys.isEmpty()) return orders;

        BigDecimal cap = PriceCapPolicy.capFor(refClose);
        if (buys.stream().noneMatch(o -> o.price().compareTo(cap) > 0)) return orders;
        return PriceCapPolicy.replaceBuysPreservingOrder(orders, INFINITE_STRATEGY.buildCappedBuyOrders(position, tradeDate, buys, cap));
    }

    // --- PRIVACY 경로 ---

    private Output runPrivacy(List<DailyCandle> candles, BacktestCommand command,
                              Map<LocalDate, PrivacyTradeBase> privacyBases) {
        PrivacyState state = new PrivacyState(command, candles.getFirst().close());
        List<String> warnings = new ArrayList<>();

        Output output = runDays(candles, state, warnings,
                (candle, nextSession) -> planPrivacyDay(state, command, privacyBases, candle, nextSession, warnings));

        // 마지막 캔들까지 이어진 결측 구간은 루프 안에서 닫힐 기회가 없다 — 여기서 flush하고 warnings를 다시 담는다
        state.flushMissingBaseGap(warnings);
        return new Output(output.points(), output.tradeCount(), output.cycleCount(), List.copyOf(warnings));
    }

    // PRIVACY 하루 처리 — 사이클 종료 판정(endsCycleOnLiquidation=true, 리버스모드 없음) 후 주문 생성
    private List<PlannedOrder> planPrivacyDay(PrivacyState state, BacktestCommand command,
                                       Map<LocalDate, PrivacyTradeBase> privacyBases, DailyCandle candle,
                                       LocalDate nextSession, List<String> warnings) {
        if (state.stopped) return List.of(); // 사이클 시드 정책으로 매매 중단된 뒤엔 주문 없음
        // 청산(어제 보유>0 → 오늘 0) 판정은 반드시 주문 생성 전 — 오늘 주문은 새 사이클 개장 자산(cycleStartAmount) 기준이어야 한다
        if (state.balance.holdings() == 0 && state.prevDayHoldings > 0
                && !state.restartCycle(command.cycleSeedTypeOrMax(), candle.date(), warnings)) {
            return List.of();
        }
        // 주문 생성은 보유수량을 바꾸지 않으므로 여기서 "오늘 종료 시점 보유수량"을 확정해도 안전하다
        state.prevDayHoldings = state.balance.holdings();

        return planPrivacyOrders(state, command, privacyBases, candle, nextSession, warnings);
    }

    // 다음 세션 주문 생성 — PlanContext 조립 후 기존 PrivacyCycleOrderStrategy.plan()에 위임
    // 배수(multiple = initialUsdDeposit ÷ currentCycleStart)는 PrivacyStrategy가 내부에서 산출한다 — 여기서 재계산하지 않는다
    // privacyBases 키는 기준표가 적용되는 세션 날짜다 — 오늘 만든 주문은 다음 세션에 체결되므로 다음 세션의 기준표를 쓴다
    // (첫 캔들 세션은 그 전날 계획 단계가 없어 매매 없음 — 운영도 등록 다음 배치부터 주문한다)
    private List<PlannedOrder> planPrivacyOrders(PrivacyState state, BacktestCommand command,
                                          Map<LocalDate, PrivacyTradeBase> privacyBases, DailyCandle candle,
                                          LocalDate nextSession, List<String> warnings) {
        if (nextSession == null) return List.of(); // 마지막 캔들 — 체결될 세션이 없다
        PrivacyTradeBase base = privacyBases.get(nextSession); // 없으면 null — plan()이 스스로 Optional.empty()를 낸다

        // 결측은 구간 단위로 1건만 요약 기록(세션 날짜 기준) — 데이터 시작일 이전 구간이 수백 일 이어져도 경고가 폭주하지 않는다
        if (base == null) state.recordMissingBase(nextSession);
        else state.flushMissingBaseGap(warnings);

        // currentPrice 자리의 전일종가(= 오늘 종가)는 PrivacyCycleOrderStrategy.plan()이 소비하지 않는다 — VR/INFINITE와의 조립 일관성 목적
        BigDecimal refClose = candle.close();
        CycleOrderStrategy.PlanContext.PrivacyInputs privacyInputs =
                new CycleOrderStrategy.PlanContext.PrivacyInputs(
                        state.cycleStartAmount, base == null ? null : base.toPlan(), refClose);
        CycleOrderStrategy.PlanContext ctx = new CycleOrderStrategy.PlanContext(
                state.balance, command.ticker(), candle.date(), "backtest", null, privacyInputs, null);

        List<PlannedOrder> orders = strategies.of(StrategyType.PRIVACY).plan(ctx)
                .map(CycleOrderStrategy.OrderPlan::orders).orElse(List.of());
        return applyPrivacyBuyCap(orders, refClose);
    }

    // 접수 전 BUY 가격 캡 보정 — 운영 PrivacyCycleOrderStrategy.capBuyOrders()와 동일 규칙
    // cap 초과 BUY만 가격을 cap으로 치환하고 수량은 건드리지 않는다 (VR/INFINITE와 달리 재산정 자체가 없다)
    private static List<PlannedOrder> applyPrivacyBuyCap(List<PlannedOrder> orders, BigDecimal refClose) {
        if (orders.isEmpty()) return orders;
        BigDecimal cap = PriceCapPolicy.capFor(refClose);
        return orders.stream()
                .map(o -> o.direction() == BUY && o.price().compareTo(cap) > 0 ? o.withPrice(cap) : o)
                .toList();
    }

    // --- 전략 공통 헬퍼 ---

    // VR 상세 — 램프 8파라미터는 운영 전략 등록과 같은 기본값 표로 정규화된 값(BacktestCommand.vrRamp())을 그대로 쓴다
    private static StrategyVrDetail vrDetail(BacktestCommand command) {
        VrRampParams ramp = command.vrRamp();
        return new StrategyVrDetail(null, command.vrIntervalWeeks(), command.vrBandWidth(),
                command.vrRecurringAmount(), ramp.initialGradient(), ramp.gGraceWeeks(), ramp.gStepWeeks(),
                ramp.gMax(), ramp.initialPoolLimitRate(), ramp.pGraceWeeks(), ramp.pStepWeeks(), ramp.poolLimitFloor());
    }

    // 초기 V값 — 운영 StrategyCreationService.resolveVrValue()와 동일 우선순위: 직접 입력(>0)이 있으면 그 값,
    // 없으면 보유분 평가금(시작 시점 시장가 × 보유수량). 등록 시점 전일종가 대신 첫 캔들 종가로 근사한다(PrivacyState와 동일 근사)
    // 보유분이 없으면 0 — VrStrategy bootstrap 경로로 첫 포지션을 만든다
    public static BigDecimal initialVrValue(BacktestCommand command, BigDecimal day0Close) {
        BigDecimal explicit = command.vrInitialValue();
        return explicit != null && explicit.signum() > 0 ? explicit : initialStockValue(command, day0Close);
    }

    // 시작 보유분 시장가 평가금 = 첫 캔들 종가 × 보유수량 (보유 없으면 0)
    public static BigDecimal initialStockValue(BacktestCommand command, BigDecimal day0Close) {
        int holdings = command.initialHoldings() != null ? command.initialHoldings() : 0;
        return holdings > 0
                ? day0Close.multiply(BigDecimal.valueOf(holdings)).setScale(2, HALF_UP)
                : BigDecimal.ZERO;
    }

    // 보유분 시장가 평가액 = 종가 × 보유수량
    private static BigDecimal marketValue(DailyCandle candle, int holdings) {
        return candle.close().multiply(BigDecimal.valueOf(holdings));
    }

    // 사이클 매수 상한 = 개장 예수금 × poolLimitRate (scale=2 HALF_UP)
    private static BigDecimal poolLimitOf(BigDecimal openPool, BigDecimal poolLimitRate) {
        return openPool.multiply(poolLimitRate).setScale(2, HALF_UP);
    }

    // 전략 공통 루프 상태 — 잔고·원금·집계 카운터. 전략별 상태는 서브클래스가 얹는다
    private static class DayState {
        AccountBalance balance;   // 현재 잔고
        BigDecimal principal;     // 원금 (시드 + 시작 보유분 평가금 + 실제 반영된 적립/인출 누계)
        BigDecimal idleCash = BigDecimal.ZERO; // 전략 밖 유휴 현금 — MAINTAIN 재시작 때 시작 금액을 넘는 초과분(평가금엔 포함)
        BigDecimal cycleStartAmount; // 현재 사이클 시작 금액 — 운영 StrategyCycle.startAmount(개장 예수금 + 개장 보유분 시장가)
        boolean stopped;          // 사이클 시드 정책(NONE·MAINTAIN 잔고 부족)으로 매매를 멈췄는지
        int tradeCount;           // 체결 건수 누계
        int cycleCount = 1;       // 진행된 사이클 수

        // 중간부터 시작 — initialHoldings>0이면 avgPrice를 평단가로 두고 시작 보유분을 잔고에 반영한다
        // 원금·사이클 시작 금액의 보유분은 첫 캔들 종가 평가금 — 수익률 기준(첫 포인트 총자산)과 같은 기준이다
        // (운영 startAmount는 등록 시점 시장가지만 백테스트엔 그 조회가 없어 첫 캔들 종가로 근사)
        DayState(BacktestCommand command, BigDecimal day0Close) {
            int holdings = command.initialHoldings() != null ? command.initialHoldings() : 0;
            BigDecimal avgPrice = holdings > 0 ? command.initialAvgPrice() : null;
            if (holdings > 0 && avgPrice == null) {
                throw new IllegalArgumentException("보유 수량(initialHoldings)이 있으면 평단가(initialAvgPrice)가 필요합니다");
            }
            BigDecimal seed = command.seedOrZero();
            this.balance = new AccountBalance(holdings, avgPrice, seed);
            this.principal = seed.add(initialStockValue(command, day0Close));
            this.cycleStartAmount = principal;
        }

        // 주문 가능 현금 = 전략 잔고 예수금 + 유휴 현금 — 운영 예산 검증이 보는 증권사 실잔고에 해당
        BigDecimal availableCash() {
            return balance.usdDeposit().add(idleCash);
        }

        // 체결로 전략 잔고 예수금이 음수가 되면 유휴 현금에서 메운다 (유휴 현금이 없으면 예산 검증상 음수가 될 수 없다)
        void coverShortfallFromIdle() {
            if (balance.usdDeposit().signum() >= 0) return;
            idleCash = idleCash.add(balance.usdDeposit());
            balance = balance.withUsdDeposit(BigDecimal.ZERO);
        }

        // 청산 후 사이클 재시작 — 운영 CycleRotationService의 잔고검증 ON 의미론(백테스트 현금은 실제 돈이라 원장만 믿는 OFF는 없는 돈을 만든다)
        // false면 매매 중단: NONE은 운영처럼 전략 일시정지, MAINTAIN은 가용 현금이 시작 금액에 못 미치면 일시정지
        boolean restartCycle(StrategyCycleSeedType seedType, LocalDate date, List<String> warnings) {
            switch (seedType) {
                case NONE -> {
                    stopped = true;
                    warnings.add(date + ": 사이클이 종료되어 이후 매매를 중단했습니다(사이클 종료 후 재시작 안 함).");
                    return false;
                }
                case MAINTAIN -> {
                    BigDecimal cash = availableCash();
                    if (cash.compareTo(cycleStartAmount) < 0) {
                        stopped = true;
                        warnings.add(date + ": 예수금이 시작 금액(" + usd(cycleStartAmount)
                                + ")보다 적어 새 사이클을 시작하지 못하고 매매를 중단했습니다.");
                        return false;
                    }
                    // 시작 금액만 전략 잔고로, 나머지는 유휴 현금으로 — 다음 사이클도 같은 시작 금액을 유지한다
                    balance = balance.withUsdDeposit(cycleStartAmount);
                    idleCash = cash.subtract(cycleStartAmount);
                }
                case MAX -> cycleStartAmount = balance.usdDeposit(); // 청산 시점 예수금 전액 이월
            }
            cycleCount++;
            return true;
        }

        // 체결 반영 — 잔고·체결건수 갱신
        void applyFills(List<Execution> executions) {
            if (executions.isEmpty()) return;
            // broker 체결 → 잔고 재계산용 Fill (matching이 broker를 참조하지 않도록 호출부에서 변환)
            List<AccountBalance.Fill> fills = executions.stream()
                    .map(e -> (AccountBalance.Fill) new AccountBalance.Fill() {
                        @Override public OrderDirection direction() { return e.direction(); }
                        @Override public int quantity() { return e.quantity(); }
                        @Override public BigDecimal amountUsd() { return e.amountUsd(); }
                    })
                    .toList();
            balance = balance.applyExecutions(fills);
            tradeCount += executions.size();
        }
    }

    // VR 루프의 가변 상태 — 롤오버가 여러 값을 한꺼번에 갱신해야 해 record 대신 가변 홀더로 둔다
    private static final class VrState extends DayState {
        BigDecimal value;                      // 현재 V값
        final LocalDate firstCycleStartDate;   // 전략 최초 사이클 시작일 — 램프 경과 주수 기준(불변)
        LocalDate cycleStartDate;              // 현재 사이클 시작일 — 롤오버 도래 판정 기준
        BigDecimal poolLimit;                  // 이번 사이클 매수 상한
        BigDecimal poolUsed = BigDecimal.ZERO; // 이번 사이클 매수 체결 누계
        String holdReason;                     // 진행 중인 롤오버 보류 사유 문구(날짜 제외) — 같은 사유 경고 중복 방지
        DailyCandle dueEvaluationCandle;       // 현재 사이클 due일 이하 마지막 캔들 — 롤오버 평가 기준(휴장 due일 보정)

        VrState(BacktestCommand command, StrategyVrDetail detail, DailyCandle firstCandle) {
            super(command, firstCandle.close());
            this.value = initialVrValue(command, firstCandle.close());
            this.firstCycleStartDate = firstCandle.date();
            this.cycleStartDate = firstCandle.date();
            this.poolLimit = poolLimitOf(command.seedOrZero(), detail.poolLimitRateAt(0));
        }

        // 공통 체결 반영에 이번 사이클 매수 사용액 누계를 덧붙인다
        @Override
        void applyFills(List<Execution> executions) {
            if (executions.isEmpty()) return;
            super.applyFills(executions);
            poolUsed = poolUsed.add(executions.stream()
                    .filter(e -> e.direction() == OrderDirection.BUY)
                    .map(Execution::amountUsd)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
        }

        // 롤오버 보류 경고 — 매 거래일 재판정되므로 같은 사유는 보류 구간당 1회만, 사유가 바뀌면 새로 남긴다
        void warnHold(List<String> warnings, String message) {
            String reason = message.substring(message.indexOf(':'));
            if (reason.equals(holdReason)) return;
            warnings.add(message);
            holdReason = reason;
        }

        // 적립·인출을 실제 예수금에 반영 — 운영 VrCycleRolloverService의 adjustedPool과 동일(음수 여부는 호출 전 보류 판정으로 걸러진다)
        void applyRecurringCashFlow(int recurringAmount) {
            if (recurringAmount == 0) return;
            BigDecimal amount = BigDecimal.valueOf(recurringAmount);
            principal = principal.add(amount);
            balance = balance.withUsdDeposit(balance.usdDeposit().add(amount));
        }
    }

    // INFINITE 루프의 가변 상태 — 리버스모드 상태 머신 + 사이클 스코프 별지점 윈도우
    private static final class InfiniteState extends DayState {
        final int divisionCount;                                  // 분할 수 — 사이클 전체에서 고정(재등록 시 변경은 범위 밖)
        boolean reverseMode;                                      // 현재 리버스모드 여부
        boolean isFirstReverseDay;                                // 오늘이 리버스모드 진입 첫날인지
        int prevDayHoldings;                                      // 어제 이터레이션 종료 시점 보유수량 — 청산(사이클 종료) 판정용
        final Deque<BigDecimal> recentCloses = new ArrayDeque<>(); // 현재 사이클 최근 종가(최대 5개) — 별지점 산출용

        InfiniteState(BacktestCommand command, BigDecimal day0Close) {
            super(command, day0Close);
            this.divisionCount = command.divisionCount() != null
                    ? command.divisionCount() : StrategyDefaults.DEFAULT_DIVISION_COUNT;
            // 시작 보유분이 있으면 청산 판정 기준선도 그만큼에서 출발 — 0으로 두면 매매 없는 첫날에도 오탐은 없지만(§엔진 주석 참고) 명시적으로 맞춰둔다
            this.prevDayHoldings = this.balance.holdings();
        }

        // 오늘 종가를 별지점 윈도우에 append — 6개째부터 가장 오래된 값을 버린다
        void pushClose(BigDecimal close) {
            recentCloses.addLast(close);
            if (recentCloses.size() > STAR_POINT_WINDOW) recentCloses.removeFirst();
        }

        // 리버스모드 상태 전이 — 전이 공식은 InfinitePosition.nextReverseMode에 그대로 위임
        void applyReverseModeTransition(StrategyTicker ticker, BigDecimal closingPrice) {
            boolean prevReverseMode = reverseMode;
            InfinitePosition probe = new InfinitePosition(balance, ticker, closingPrice, divisionCount);
            boolean nextReverseMode = probe.nextReverseMode(prevReverseMode);
            isFirstReverseDay = !prevReverseMode && nextReverseMode;
            reverseMode = nextReverseMode;
        }

        // 별지점 = 현재 사이클 최근 종가 평균(scale=2, HALF_UP) — 리버스모드 2일차부터만 사용(첫날은 MOC 즉시 청산)
        BigDecimal starPointPrice() {
            if (!reverseMode || isFirstReverseDay || recentCloses.isEmpty()) return null;
            BigDecimal sum = recentCloses.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            return sum.divide(BigDecimal.valueOf(recentCloses.size()), 2, HALF_UP);
        }

        // 새 사이클 시작 — 리버스모드·별지점 윈도우 리셋 (자산 처리는 restartCycle의 시드 정책 몫)
        void resetReverseMode() {
            reverseMode = false;
            isFirstReverseDay = false;
            recentCloses.clear();
        }
    }

    // PRIVACY 루프의 가변 상태 — 결측 구간 요약용 커서 (배수 산출 기준 자산은 공통 cycleStartAmount)
    private static final class PrivacyState extends DayState {
        int prevDayHoldings;          // 어제 이터레이션 종료 시점 보유수량 — 청산(사이클 종료) 판정용
        LocalDate missingBaseFrom;    // 진행 중인 기준 매매표 결측 구간 시작일 (없으면 null)
        LocalDate missingBaseTo;      // 진행 중인 결측 구간 마지막 날
        int missingBaseDays;          // 진행 중인 결측 구간 일수

        PrivacyState(BacktestCommand command, BigDecimal day0Close) {
            super(command, day0Close);
            this.prevDayHoldings = balance.holdings();
        }

        // 오늘을 진행 중인 결측 구간에 편입 — 경고는 구간이 닫힐 때 1건만 기록한다
        void recordMissingBase(LocalDate date) {
            if (missingBaseFrom == null) missingBaseFrom = date;
            missingBaseTo = date;
            missingBaseDays++;
        }

        // 결측 구간 종료 — 누적된 구간을 한 줄로 요약해 남기고 커서를 비운다
        void flushMissingBaseGap(List<String> warnings) {
            if (missingBaseFrom == null) return;
            warnings.add(missingBaseFrom + " ~ " + missingBaseTo + "(총 " + missingBaseDays + "일): 기준 매매표가 없어 매매하지 않았습니다.");
            missingBaseFrom = null;
            missingBaseTo = null;
            missingBaseDays = 0;
        }
    }
}
