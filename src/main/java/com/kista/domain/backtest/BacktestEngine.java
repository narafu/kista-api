package com.kista.domain.backtest;

import com.kista.domain.model.backtest.BacktestCommand;
import com.kista.domain.model.backtest.BacktestPoint;
import com.kista.domain.model.backtest.DailyCandle;
import com.kista.broker.domain.model.Execution;
import com.kista.trading.domain.model.Order;
import com.kista.privacy.domain.model.PrivacyTradeBase;
import com.kista.trading.domain.model.AccountBalance;
import com.kista.trading.domain.model.InfinitePosition;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyVrDetail;
import com.kista.trading.domain.model.VrPosition;
import com.kista.trading.domain.strategy.CycleOrderStrategies;
import com.kista.trading.domain.strategy.CycleOrderStrategy;
import com.kista.trading.domain.strategy.InfiniteStrategy;
import com.kista.trading.domain.strategy.PriceCapPolicy;
import com.kista.trading.domain.strategy.VrStrategy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.kista.trading.domain.model.Order.OrderDirection.BUY;
import static java.math.RoundingMode.HALF_UP;

// 백테스트 시뮬레이션 엔진 — 일봉을 하루씩 진행하며 기존 전략 순수 함수를 올바른 순서로 호출한다
// 새 매매 수식은 하나도 만들지 않는다: 주문 생성·V값 갱신·가격 캡·램프는 전부 domain/strategy·domain/model/strategy에 위임
// domain/backtest는 ArchUnit @Component 허용 예외(domain/strategy)에 없으므로 Spring 빈 금지 — 평범한 생성자 주입
public class BacktestEngine {

    // 캡 재산정(사다리 재생성) 전용 — VrStrategy는 무상태라 인스턴스 공유 가능
    private static final VrStrategy VR_STRATEGY = new VrStrategy();
    // 캡 재산정(수량 재계산 + 보정 주문) 전용 — InfiniteStrategy도 무상태
    private static final InfiniteStrategy INFINITE_STRATEGY = new InfiniteStrategy();
    // VR 램프 유예·단계 주수 기본값 — 운영 StrategyService.normalizeVrRampParams()와 동일
    private static final int DEFAULT_GRACE_WEEKS = 52;
    private static final int DEFAULT_STEP_WEEKS = 26;
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
        if (command.type() != Strategy.Type.PRIVACY) return run(candles, command);
        if (candles.isEmpty()) return new Output(List.of(), 0, 0, List.of());
        return runPrivacy(candles, command, privacyBases);
    }

    // --- 전략 공통 일봉 루프 ---

    // 전략별 하루 처리 콜백 — 체결·자산 기록이 끝난 뒤 호출돼 "내일 체결 대상" 주문을 반환한다
    @FunctionalInterface
    private interface DayPlanner {
        List<Order> planFor(DailyCandle candle, BigDecimal prevClose);
    }

    // 전략 무관 일봉 루프 — look-ahead 불변조건(어제 주문만 오늘 체결 / prevClose는 루프 최하단 갱신)을 여기 한 곳에서만 관리한다
    // tradingStart 이전 캔들은 전일종가 확보용 워밍업 프리픽스로 취급 — 체결·기록·주문생성을 전혀 하지 않는다
    private Output runDays(List<DailyCandle> candles, LocalDate tradingStart, DayState state,
                           List<String> warnings, DayPlanner planner) {
        List<BacktestPoint> points = new ArrayList<>();
        List<Order> pending = List.of(); // 어제 생성한 주문 — 오늘 캔들로 체결 판정
        BigDecimal prevClose = null;     // 전일 종가 — referencePrice·캡 기준가 공용
        // 예수금 플로어 연속 발동구간 커서 — VR valueHoldWarned·PRIVACY 결측요약과 동일 취지로 일별 경고 폭주를 막는다
        LocalDate floorFrom = null;
        LocalDate floorTo = null;
        int floorDays = 0;
        BigDecimal floorMaxShortfall = BigDecimal.ZERO;

        for (DailyCandle candle : candles) {
            // 워밍업 구간: 매매를 시뮬레이션하지 않고 전일종가만 이월한다
            if (candle.date().isBefore(tradingStart)) {
                prevClose = candle.close();
                continue;
            }

            // (1) 어제 주문을 오늘 캔들로 체결 — 오늘 만든 주문은 오늘 체결하지 않는다(look-ahead 방지 핵심 불변조건)
            state.applyFills(FillSimulator.simulate(pending, candle));

            // 체결 후 예수금이 음수면 0으로 조정 — INFINITE 최소 1주 강제·PRIVACY 배수 과대 산출로 시드를 넘겨 살 수 있다
            // VrState.applyRecurringCashFlow의 인출 클램프와 동일 성격의 방어이며, poolUsed 등 전략별 누계는 이미
            // applyFills 안에서 클램프 전 실제 체결금액으로 계산이 끝난 뒤라 영향받지 않는다(이월 잔고에만 바닥을 둔다)
            // PRIVACY는 initialUsdDeposit이 다음 청산 때까지 안 바뀌고 INFINITE는 매일 최소 1주를 강제해 재발 가능 — 연속구간을
            // 하루 1건씩 쌓지 않고 구간이 끊길 때(또는 루프 종료)만 1건으로 요약한다
            if (state.balance.usdDeposit().signum() < 0) {
                BigDecimal shortfall = state.balance.usdDeposit().negate();
                if (floorFrom == null) floorFrom = candle.date();
                floorTo = candle.date();
                floorDays++;
                if (shortfall.compareTo(floorMaxShortfall) > 0) floorMaxShortfall = shortfall;
                state.balance = new AccountBalance(state.balance.holdings(), state.balance.avgPrice(), BigDecimal.ZERO);
            } else if (floorFrom != null) {
                warnings.add(floorGapWarning(floorFrom, floorTo, floorDays, floorMaxShortfall));
                floorFrom = null;
                floorMaxShortfall = BigDecimal.ZERO;
                floorDays = 0;
            }

            // (2) 오늘 EOD 자산 기록 — 보유분은 평단가가 아닌 종가 시장가로 평가
            points.add(new BacktestPoint(candle.date(),
                    state.balance.usdDeposit().add(marketValue(candle, state.balance.holdings())),
                    state.principal));

            // (3) 전략별 하루 처리 — 사이클 판정 후 오늘 주문 생성 + 접수 전 BUY 가격 캡 보정
            pending = planner.planFor(candle, prevClose);

            // prevClose 갱신은 반드시 루프 최하단 — 오늘 주문은 "전일" 종가까지만 볼 수 있어야 한다(첫날은 null → 캡·bootstrap 없음)
            prevClose = candle.close();
        }
        // 마지막 캔들까지 이어진 플로어 구간은 루프 안에서 닫힐 기회가 없다 — 여기서 flush
        if (floorFrom != null) warnings.add(floorGapWarning(floorFrom, floorTo, floorDays, floorMaxShortfall));
        // 마지막 pending은 체결 기회가 없어 자연히 버려진다
        return new Output(List.copyOf(points), state.tradeCount, state.cycleCount, List.copyOf(warnings));
    }

    // 예수금 플로어 연속구간 1건 요약 — PrivacyState.flushMissingBaseGap과 동일 포맷 관용구
    private static String floorGapWarning(LocalDate from, LocalDate to, int days, BigDecimal maxShortfall) {
        return "체결 후 예수금 부족으로 0 조정: " + from + "~" + to + ", 총 " + days + "일, 최대 부족액=" + maxShortfall;
    }

    // --- VR 경로 ---

    private Output runVr(List<DailyCandle> candles, BacktestCommand command) {
        StrategyVrDetail detail = syntheticVrDetail(command);
        VrState state = new VrState(command, detail, candles.getFirst().date());
        List<String> warnings = new ArrayList<>();

        // VR엔 워밍업 프리픽스 개념이 없다(전일종가 없이도 사다리가 성립) — 첫 캔들을 거래 시작일로 넘겨 pre-start skip을 no-op으로 만든다
        return runDays(candles, candles.getFirst().date(), state, warnings, (candle, prevClose) -> {
            // 롤오버 판정 — 오늘 체결까지 반영한 잔고 기준으로 판정해야 오늘 새 사이클의 첫 주문이 나온다
            rolloverIfDue(state, command, detail, candle, warnings);
            return planVrOrders(state, command, candle, prevClose);
        });
    }

    // VR N주 롤오버 — 운영 VrCycleRolloverService와 동일 순서(조정 전 예수금으로 V′ 계산 → V′≤0이면 보류)
    private void rolloverIfDue(VrState state, BacktestCommand command, StrategyVrDetail detail,
                               DailyCandle candle, List<String> warnings) {
        // due 조건: 사이클 시작일 + intervalWeeks ≤ 오늘 (당일 포함)
        if (candle.date().isBefore(state.cycleStartDate.plusWeeks(command.vrIntervalWeeks()))) return;

        // 램프 기준 경과 주수는 전략 최초 사이클 시작일(= 백테스트 시작일) 기준 — 사이클마다 리셋하지 않는다
        long weeks = ChronoUnit.WEEKS.between(state.firstCycleStartDate, candle.date());
        BigDecimal evaluation = marketValue(candle, state.balance.holdings());
        BigDecimal newValue = VrPosition.nextValue(state.value, state.balance.usdDeposit(),
                detail.gradientAt(weeks), command.vrRecurringAmount(), evaluation);

        // V′≤0이면 롤오버 보류 — cycleStartDate를 갱신하지 않아 다음 거래일에 재판정한다
        if (newValue.signum() <= 0) {
            // 매 거래일 재판정되므로 경고는 보류 구간당 1회만 남긴다(수년 구간에서 동일 문구 수백 건 누적 방지)
            if (!state.valueHoldWarned) {
                warnings.add("VR 롤오버 보류(V'<=0): date=" + candle.date());
                state.valueHoldWarned = true;
            }
            return;
        }

        state.applyRecurringCashFlow(command.vrRecurringAmount(), candle.date(), warnings);
        state.value = newValue;
        state.cycleStartDate = candle.date();
        // 새 사이클의 poolLimit — 자본 조정까지 반영한 개장 예수금 × 램프 재계산 비율
        state.poolLimit = poolLimitOf(state.balance.usdDeposit(), detail.poolLimitRateAt(weeks));
        state.poolUsed = BigDecimal.ZERO;
        state.cycleCount++;
        state.valueHoldWarned = false;
    }

    // 오늘 주문 생성 — PlanContext 조립 후 기존 VrCycleOrderStrategy.plan()에 위임
    private List<Order> planVrOrders(VrState state, BacktestCommand command, DailyCandle candle, BigDecimal prevClose) {
        // referencePrice·currentPrice 모두 전일 종가로 채운다 — 백테스트엔 장중 재조회 현재가가 없다(알려진 근사)
        CycleOrderStrategy.PlanContext.VrInputs vrInputs = new CycleOrderStrategy.PlanContext.VrInputs(
                state.value, command.vrBandWidth(), state.poolLimit, state.poolUsed,
                prevClose, prevClose, command.vrRecurringAmount());
        CycleOrderStrategy.PlanContext ctx = new CycleOrderStrategy.PlanContext(
                state.balance, syntheticStrategy(command), candle.date(), "backtest", null, null, vrInputs);

        Optional<CycleOrderStrategy.OrderPlan> plan = strategies.of(Strategy.Type.VR).plan(ctx);
        List<Order> orders = plan.map(CycleOrderStrategy.OrderPlan::orders).orElse(List.of());
        // 캡 재산정에는 plan()이 이미 조립해 실어 보낸 VrPosition을 그대로 재사용한다(운영 BuyOrderPriceCapper와 동일 계약)
        return applyVrBuyCap(orders, prevClose,
                plan.map(CycleOrderStrategy.OrderPlan::vrPosition).orElse(null), command.ticker(), candle.date());
    }

    // 접수 전 BUY 가격 캡 보정 — 운영 BuyOrderPriceCapper(VR_POSITION)와 동일 규칙, 현재가 대용으로 전일 종가 사용
    private List<Order> applyVrBuyCap(List<Order> orders, BigDecimal prevClose, VrPosition position,
                                      Strategy.Ticker ticker, LocalDate tradeDate) {
        if (prevClose == null || position == null) return orders;
        List<Order> buys = orders.stream().filter(o -> o.direction() == BUY).toList();
        if (buys.isEmpty()) return orders;
        // bootstrap 배치(LOC)는 사다리 공식과 무관한 별도 산정가라 재산정 대상이 아니다 — BuyOrderPriceCapper.isVrBootstrapShaped와 동일 판정
        if (buys.stream().anyMatch(o -> o.orderType() == Order.OrderType.LOC)) return orders;

        BigDecimal cap = PriceCapPolicy.capFor(prevClose);
        if (buys.stream().noneMatch(o -> o.price().compareTo(cap) > 0)) return orders;
        return replaceBuysPreservingOrder(orders, VR_STRATEGY.buildCappedBuyOrders(position, ticker, tradeDate, cap));
    }

    // --- INFINITE 경로 ---

    private Output runInfinite(List<DailyCandle> candles, BacktestCommand command) {
        InfiniteState state = new InfiniteState(command);
        List<String> warnings = new ArrayList<>();

        // INFINITE는 0회차에 전일종가가 필수라 command.from() 이전 캔들을 워밍업 프리픽스로 소비한다(매매는 from부터)
        return runDays(candles, command.from(), state, warnings,
                (candle, prevClose) -> planInfiniteDay(state, command, candle, prevClose, warnings));
    }

    // INFINITE 하루 처리 — 순서 고정: 별지점 윈도우 갱신 → 리버스모드 전이 → 사이클 종료 판정 → 주문 생성
    private List<Order> planInfiniteDay(InfiniteState state, BacktestCommand command, DailyCandle candle,
                                        BigDecimal prevClose, List<String> warnings) {
        // 오늘 종가는 리버스모드 여부와 무관하게 매일 윈도우에 쌓는다(사이클 스코프 — 종료 시 함께 비워짐)
        state.pushClose(candle.close());

        // 리버스모드 전이 — 오늘 체결까지 반영한 잔고와 오늘 종가로 판정(운영 CyclePositionPersistor.computeNewReverseMode와 동일 타이밍)
        state.applyReverseModeTransition(command.ticker(), candle.close());

        // 청산(어제 보유>0 → 오늘 0) 판정은 반드시 주문 생성 전 — 오늘 주문은 새 사이클의 0회차 주문이어야 한다
        if (state.balance.holdings() == 0 && state.prevDayHoldings > 0) state.rotateCycle();
        // 주문 생성은 보유수량을 바꾸지 않으므로 여기서 "오늘 종료 시점 보유수량"을 확정해도 안전하다(모든 분기 공통 통과 지점)
        state.prevDayHoldings = state.balance.holdings();

        return planInfiniteOrders(state, command, candle, prevClose, warnings);
    }

    // 오늘 주문 생성 — PlanContext 조립 후 기존 InfiniteCycleOrderStrategy.plan()에 위임
    private List<Order> planInfiniteOrders(InfiniteState state, BacktestCommand command, DailyCandle candle,
                                           BigDecimal prevClose, List<String> warnings) {
        // 0회차(holdings=0)에 전일종가가 없으면 운영 planNormalMode()가 예외를 던진다 — 호출 전에 방어하고 그날만 주문을 생략한다
        if (state.balance.holdings() == 0 && prevClose == null) {
            warnings.add("전일종가 없음, 첫 거래일 주문 생략: date=" + candle.date());
            return List.of();
        }

        CycleOrderStrategy.PlanContext.InfiniteInputs infiniteInputs = new CycleOrderStrategy.PlanContext.InfiniteInputs(
                state.divisionCount, prevClose, state.starPointPrice(), state.reverseMode, state.isFirstReverseDay);
        CycleOrderStrategy.PlanContext ctx = new CycleOrderStrategy.PlanContext(
                state.balance, syntheticStrategy(command), candle.date(), "backtest", infiniteInputs, null, null);

        Optional<CycleOrderStrategy.OrderPlan> plan = strategies.of(Strategy.Type.INFINITE).plan(ctx);
        List<Order> orders = plan.map(CycleOrderStrategy.OrderPlan::orders).orElse(List.of());
        // 리버스모드면 position이 null — 운영 BuyOrderPriceCapper와 동일하게 캡 재산정 대상에서 제외된다
        return applyInfiniteBuyCap(orders, prevClose,
                plan.map(CycleOrderStrategy.OrderPlan::position).orElse(null), candle.date());
    }

    // 접수 전 BUY 가격 캡 보정 — 운영 BuyOrderPriceCapper(INFINITE_POSITION)와 동일 규칙, 현재가 대용으로 전일 종가 사용
    private List<Order> applyInfiniteBuyCap(List<Order> orders, BigDecimal prevClose, InfinitePosition position,
                                            LocalDate tradeDate) {
        if (prevClose == null || position == null) return orders;
        List<Order> buys = orders.stream().filter(o -> o.direction() == BUY).toList();
        if (buys.isEmpty()) return orders;

        BigDecimal cap = PriceCapPolicy.capFor(prevClose);
        if (buys.stream().noneMatch(o -> o.price().compareTo(cap) > 0)) return orders;
        return replaceBuysPreservingOrder(orders, INFINITE_STRATEGY.buildCappedBuyOrders(position, tradeDate, buys, cap));
    }

    // --- PRIVACY 경로 ---

    private Output runPrivacy(List<DailyCandle> candles, BacktestCommand command,
                              Map<LocalDate, PrivacyTradeBase> privacyBases) {
        PrivacyState state = new PrivacyState(command, candles.getFirst().close());
        List<String> warnings = new ArrayList<>();

        // PRIVACY도 VR과 마찬가지로 워밍업 프리픽스가 필요 없다(전일종가가 없으면 캡만 생략될 뿐 예외가 없다)
        Output output = runDays(candles, candles.getFirst().date(), state, warnings,
                (candle, prevClose) -> planPrivacyDay(state, command, privacyBases, candle, prevClose, warnings));

        // 마지막 캔들까지 이어진 결측 구간은 루프 안에서 닫힐 기회가 없다 — 여기서 flush하고 warnings를 다시 담는다
        state.flushMissingBaseGap(warnings);
        return new Output(output.points(), output.tradeCount(), output.cycleCount(), List.copyOf(warnings));
    }

    // PRIVACY 하루 처리 — 사이클 종료 판정(endsCycleOnLiquidation=true, 리버스모드 없음) 후 주문 생성
    private List<Order> planPrivacyDay(PrivacyState state, BacktestCommand command,
                                       Map<LocalDate, PrivacyTradeBase> privacyBases, DailyCandle candle,
                                       BigDecimal prevClose, List<String> warnings) {
        // 청산(어제 보유>0 → 오늘 0) 판정은 반드시 주문 생성 전 — 오늘 주문은 새 사이클 개장 자산 기준이어야 한다
        if (state.balance.holdings() == 0 && state.prevDayHoldings > 0) {
            state.initialUsdDeposit = state.balance.usdDeposit(); // 새 사이클 개장 자산 — 자산 이월이지 시드 리셋이 아니다
            state.cycleCount++;
        }
        // 주문 생성은 보유수량을 바꾸지 않으므로 여기서 "오늘 종료 시점 보유수량"을 확정해도 안전하다
        state.prevDayHoldings = state.balance.holdings();

        return planPrivacyOrders(state, command, privacyBases, candle, prevClose, warnings);
    }

    // 오늘 주문 생성 — PlanContext 조립 후 기존 PrivacyCycleOrderStrategy.plan()에 위임
    // 배수(multiple = initialUsdDeposit ÷ currentCycleStart)는 PrivacyStrategy가 내부에서 산출한다 — 여기서 재계산하지 않는다
    private List<Order> planPrivacyOrders(PrivacyState state, BacktestCommand command,
                                          Map<LocalDate, PrivacyTradeBase> privacyBases, DailyCandle candle,
                                          BigDecimal prevClose, List<String> warnings) {
        PrivacyTradeBase base = privacyBases.get(candle.date()); // 없으면 null — plan()이 스스로 Optional.empty()를 낸다

        // 결측은 구간 단위로 1건만 요약 기록 — 데이터 시작일 이전 구간이 수백 일 이어져도 경고가 폭주하지 않는다
        if (base == null) state.recordMissingBase(candle.date());
        else state.flushMissingBaseGap(warnings);

        // currentPrice 자리의 전일종가는 PrivacyCycleOrderStrategy.plan()이 소비하지 않는다 — VR/INFINITE와의 조립 일관성 목적
        CycleOrderStrategy.PlanContext.PrivacyInputs privacyInputs =
                new CycleOrderStrategy.PlanContext.PrivacyInputs(state.initialUsdDeposit, base, prevClose);
        CycleOrderStrategy.PlanContext ctx = new CycleOrderStrategy.PlanContext(
                state.balance, syntheticStrategy(command), candle.date(), "backtest", null, privacyInputs, null);

        List<Order> orders = strategies.of(Strategy.Type.PRIVACY).plan(ctx)
                .map(CycleOrderStrategy.OrderPlan::orders).orElse(List.of());
        return applyPrivacyBuyCap(orders, prevClose);
    }

    // 접수 전 BUY 가격 캡 보정 — 운영 BuyOrderPriceCapper(PRIVACY_SIMPLE)와 동일 규칙
    // cap 초과 BUY만 가격을 cap으로 치환하고 수량은 건드리지 않는다 (VR/INFINITE와 달리 재산정 자체가 없다)
    private static List<Order> applyPrivacyBuyCap(List<Order> orders, BigDecimal prevClose) {
        if (prevClose == null || orders.isEmpty()) return orders;
        BigDecimal cap = PriceCapPolicy.capFor(prevClose);
        return orders.stream()
                .map(o -> o.direction() == BUY && o.price().compareTo(cap) > 0 ? o.withPrice(cap) : o)
                .toList();
    }

    // --- 전략 공통 헬퍼 ---

    // 재산정 BUY가 원래 BUY 자리를 채우고 남는 보정 BUY는 뒤에 붙인다 — SELL은 원래 상대 순서 그대로 유지
    private static List<Order> replaceBuysPreservingOrder(List<Order> orders, List<Order> cappedBuys) {
        List<Order> replaced = new ArrayList<>(orders.size() + cappedBuys.size());
        int cappedIndex = 0;
        for (Order order : orders) {
            if (order.direction() != BUY) replaced.add(order);
            else if (cappedIndex < cappedBuys.size()) replaced.add(cappedBuys.get(cappedIndex++));
        }
        replaced.addAll(cappedBuys.subList(cappedIndex, cappedBuys.size()));
        return List.copyOf(replaced);
    }

    // 백테스트용 합성 전략 — 계좌·PK 없이 타입/종목만 유효한 값으로 채운다(plan()이 type·ticker만 참조)
    private static Strategy syntheticStrategy(BacktestCommand command) {
        return new Strategy(null, null, command.type(), Strategy.Status.ACTIVE,
                command.ticker(), Strategy.CycleSeedType.NONE);
    }

    // 합성 VR 상세 — 램프 8파라미터는 백테스트 입력으로 받지 않고 운영의 recurringMode 고정값 표(RAMP_DEFAULTS_BY_MODE와 동기화)를 그대로 쓴다
    // gMax=initialGradient, poolLimitFloor=initialPoolLimitRate로 두면 gradientAt()/poolLimitRateAt()의 상하한 클램프가
    // 항상 초기값을 돌려준다 — 즉 "램프 없음, 초기값 고정"(백테스트는 램프 자체를 모델링하지 않는다는 기존 설계 유지)
    private static StrategyVrDetail syntheticVrDetail(BacktestCommand command) {
        int initialGradient = command.vrRecurringAmount() < 0 ? 40 : 10;
        BigDecimal initialPoolLimitRate = command.vrRecurringAmount() > 0 ? BigDecimal.ONE
                : command.vrRecurringAmount() == 0 ? new BigDecimal("0.75") : new BigDecimal("0.1");
        return new StrategyVrDetail(null, command.vrIntervalWeeks(), command.vrBandWidth(),
                command.vrRecurringAmount(), initialGradient, DEFAULT_GRACE_WEEKS, DEFAULT_STEP_WEEKS,
                initialGradient, initialPoolLimitRate, DEFAULT_GRACE_WEEKS, DEFAULT_STEP_WEEKS, initialPoolLimitRate);
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
        BigDecimal principal;     // 원금 (시드 + 실제 반영된 적립/인출 누계)
        int tradeCount;           // 체결 건수 누계
        int cycleCount = 1;       // 진행된 사이클 수

        // 중간부터 시작 — initialHoldings>0이면 avgPrice를 취득원가로 두고 시작 보유분을 잔고에 반영한다
        // (원가는 registration의 fetchMarketPrice와 달리 백테스트엔 등록 시점 시장가 조회가 없어 사용자가 직접 입력한 avgPrice를 그대로 쓴다)
        DayState(BacktestCommand command) {
            int holdings = command.initialHoldings() != null ? command.initialHoldings() : 0;
            BigDecimal avgPrice = holdings > 0 ? command.initialAvgPrice() : null;
            if (holdings > 0 && avgPrice == null) {
                throw new IllegalArgumentException("보유 수량(initialHoldings)이 있으면 평단가(initialAvgPrice)가 필요합니다");
            }
            BigDecimal seed = command.seedOrZero();
            this.balance = new AccountBalance(holdings, avgPrice, seed);
            // 원금 = 시드 + 기존 보유분 취득원가(시장가 아닌 실제 투입 비용 기준)
            BigDecimal initialStockCost = holdings > 0
                    ? avgPrice.multiply(BigDecimal.valueOf(holdings)).setScale(2, HALF_UP)
                    : BigDecimal.ZERO;
            this.principal = seed.add(initialStockCost);
        }

        // 체결 반영 — 잔고·체결건수 갱신
        void applyFills(List<Execution> executions) {
            if (executions.isEmpty()) return;
            balance = balance.applyExecutions(AccountBalance.Fill.listOf(executions));
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
        boolean valueHoldWarned;               // V′≤0 보류 경고 중복 방지 플래그

        VrState(BacktestCommand command, StrategyVrDetail detail, LocalDate startDate) {
            super(command);
            this.value = command.vrInitialValue() != null ? command.vrInitialValue() : BigDecimal.ZERO;
            this.firstCycleStartDate = startDate;
            this.cycleStartDate = startDate;
            this.poolLimit = poolLimitOf(command.seedOrZero(), detail.poolLimitRateAt(0));
        }

        // 공통 체결 반영에 이번 사이클 매수 사용액 누계를 덧붙인다
        @Override
        void applyFills(List<Execution> executions) {
            if (executions.isEmpty()) return;
            super.applyFills(executions);
            poolUsed = poolUsed.add(executions.stream()
                    .filter(e -> e.direction() == com.kista.broker.domain.model.Direction.BUY)
                    .map(Execution::amountUsd)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
        }

        // 적립·인출을 실제 현금흐름으로 반영 — 운영에는 없는 백테스트 전용 결정(대신 입출금해 줄 사용자가 없다)
        void applyRecurringCashFlow(int recurringAmount, LocalDate date, List<String> warnings) {
            if (recurringAmount == 0) return;
            BigDecimal before = balance.usdDeposit();
            BigDecimal adjusted = before.add(BigDecimal.valueOf(recurringAmount));
            // 인출액이 예수금을 넘으면 예수금이 음수가 되어 이후 poolLimit·잔여예산 계산이 통째로 무의미해진다 — 0에서 바닥을 둔다
            if (adjusted.signum() < 0) {
                warnings.add("인출액이 예수금을 초과해 0으로 조정: date=" + date + ", 부족액=" + adjusted.negate());
                adjusted = BigDecimal.ZERO;
            }
            // principal은 클램프 후 실제 반영된 만큼만 증감 — 요청 인출액 전부를 반영하면 원금이 과다 차감된다
            principal = principal.add(adjusted.subtract(before));
            balance = new AccountBalance(balance.holdings(), balance.avgPrice(), adjusted);
        }
    }

    // INFINITE 루프의 가변 상태 — 리버스모드 상태 머신 + 사이클 스코프 별지점 윈도우
    private static final class InfiniteState extends DayState {
        final int divisionCount;                                  // 분할 수 — 사이클 전체에서 고정(재등록 시 변경은 범위 밖)
        boolean reverseMode;                                      // 현재 리버스모드 여부
        boolean isFirstReverseDay;                                // 오늘이 리버스모드 진입 첫날인지
        int prevDayHoldings;                                      // 어제 이터레이션 종료 시점 보유수량 — 청산(사이클 종료) 판정용
        final Deque<BigDecimal> recentCloses = new ArrayDeque<>(); // 현재 사이클 최근 종가(최대 5개) — 별지점 산출용

        InfiniteState(BacktestCommand command) {
            super(command);
            this.divisionCount = command.divisionCount() != null
                    ? command.divisionCount() : Strategy.DEFAULT_DIVISION_COUNT;
            // 시작 보유분이 있으면 청산 판정 기준선도 그만큼에서 출발 — 0으로 두면 매매 없는 첫날에도 오탐은 없지만(§엔진 주석 참고) 명시적으로 맞춰둔다
            this.prevDayHoldings = this.balance.holdings();
        }

        // 오늘 종가를 별지점 윈도우에 append — 6개째부터 가장 오래된 값을 버린다
        void pushClose(BigDecimal close) {
            recentCloses.addLast(close);
            if (recentCloses.size() > STAR_POINT_WINDOW) recentCloses.removeFirst();
        }

        // 리버스모드 상태 전이 — 전이 공식은 InfinitePosition.nextReverseMode에 그대로 위임
        void applyReverseModeTransition(Strategy.Ticker ticker, BigDecimal closingPrice) {
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

        // 사이클 종료 + 즉시 재시작(백테스트 전용 규칙) — 자산은 이월하고 리버스모드·별지점 윈도우만 리셋
        void rotateCycle() {
            reverseMode = false;
            isFirstReverseDay = false;
            recentCloses.clear();
            cycleCount++;
        }
    }

    // PRIVACY 루프의 가변 상태 — 배수 산출 기준 자산 + 결측 구간 요약용 커서
    private static final class PrivacyState extends DayState {
        BigDecimal initialUsdDeposit; // 현재 사이클 개장 자산 — PrivacyStrategy 배수 산출 기준, 사이클 교체 때만 갱신
        int prevDayHoldings;          // 어제 이터레이션 종료 시점 보유수량 — 청산(사이클 종료) 판정용
        LocalDate missingBaseFrom;    // 진행 중인 기준 매매표 결측 구간 시작일 (없으면 null)
        LocalDate missingBaseTo;      // 진행 중인 결측 구간 마지막 날
        int missingBaseDays;          // 진행 중인 결측 구간 일수

        // day0Close: 시작 보유분 시장가 평가 기준 — 운영 currentCycle.startAmount()(개장 예수금+개장 보유분 시장가)와 동일 계약을
        // 재현하려면 등록 시점 시장가가 필요한데 백테스트엔 그 조회가 없어 첫 캔들 종가로 근사한다(알려진 근사)
        PrivacyState(BacktestCommand command, BigDecimal day0Close) {
            super(command);
            BigDecimal seed = command.seedOrZero();
            BigDecimal initialStockValue = balance.holdings() > 0
                    ? day0Close.multiply(BigDecimal.valueOf(balance.holdings())).setScale(2, HALF_UP)
                    : BigDecimal.ZERO;
            this.initialUsdDeposit = seed.add(initialStockValue);
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
            warnings.add("기준 매매표 없음: " + missingBaseFrom + "~" + missingBaseTo + ", 총 " + missingBaseDays + "일");
            missingBaseFrom = null;
            missingBaseTo = null;
            missingBaseDays = 0;
        }
    }
}
