package com.kista.domain.backtest;

import com.kista.domain.model.backtest.BacktestCommand;
import com.kista.domain.model.backtest.BacktestPoint;
import com.kista.domain.model.backtest.DailyCandle;
import com.kista.domain.model.broker.Execution;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyVrDetail;
import com.kista.domain.model.strategy.VrPosition;
import com.kista.domain.strategy.CycleOrderStrategies;
import com.kista.domain.strategy.CycleOrderStrategy;
import com.kista.domain.strategy.PriceCapPolicy;
import com.kista.domain.strategy.VrStrategy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.kista.domain.model.order.Order.OrderDirection.BUY;
import static java.math.RoundingMode.HALF_UP;

// 백테스트 시뮬레이션 엔진 — 일봉을 하루씩 진행하며 기존 전략 순수 함수를 올바른 순서로 호출한다
// 새 매매 수식은 하나도 만들지 않는다: 주문 생성·V값 갱신·가격 캡·램프는 전부 domain/strategy·domain/model/strategy에 위임
// domain/backtest는 ArchUnit @Component 허용 예외(domain/strategy)에 없으므로 Spring 빈 금지 — 평범한 생성자 주입
public class BacktestEngine {

    // 캡 재산정(사다리 재생성) 전용 — VrStrategy는 무상태라 인스턴스 공유 가능
    private static final VrStrategy VR_STRATEGY = new VrStrategy();
    // VR 램프 유예·단계 주수 기본값 — 운영 StrategyService.normalizeVrRampParams()와 동일
    private static final int DEFAULT_GRACE_WEEKS = 52;
    private static final int DEFAULT_STEP_WEEKS = 26;

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
            // INFINITE/PRIVACY 분기는 후속 태스크가 여기에 추가한다 — 그 전까지는 조용히 빈 결과를 내지 않고 명확히 실패시킨다
            default -> throw new IllegalArgumentException("백테스트 미지원 전략: " + command.type());
        };
    }

    // --- VR 경로 ---

    private Output runVr(List<DailyCandle> candles, BacktestCommand command) {
        StrategyVrDetail detail = syntheticVrDetail(command);
        VrState state = new VrState(command, detail, candles.getFirst().date());

        List<BacktestPoint> points = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Order> pending = List.of(); // 어제 생성한 주문 — 오늘 캔들로 체결 판정
        BigDecimal prevClose = null;     // 전일 종가 — referencePrice·캡 기준가 공용

        for (DailyCandle candle : candles) {
            // (1) 어제 주문을 오늘 캔들로 체결 — 오늘 만든 주문은 오늘 체결하지 않는다(look-ahead 방지 핵심 불변조건)
            state.applyFills(FillSimulator.simulate(pending, candle));

            // (2) 오늘 EOD 자산 기록 — 보유분은 평단가가 아닌 종가 시장가로 평가
            points.add(new BacktestPoint(candle.date(),
                    state.balance.usdDeposit().add(marketValue(candle, state.balance.holdings())),
                    state.principal));

            // (3) 롤오버 판정 — 오늘 체결까지 반영한 잔고 기준으로 판정해야 오늘 새 사이클의 첫 주문이 나온다
            rolloverIfDue(state, command, detail, candle, warnings);

            // (4)(5) 오늘 주문 생성 + 접수 전 BUY 가격 캡 보정 → 내일 체결 대상이 된다
            pending = planVrOrders(state, command, candle, prevClose);

            // prevClose 갱신은 반드시 루프 최하단 — 오늘 주문은 "전일" 종가까지만 볼 수 있어야 한다(첫날은 null → 캡·bootstrap 없음)
            prevClose = candle.close();
        }
        // 마지막 pending은 체결 기회가 없어 자연히 버려진다
        return new Output(List.copyOf(points), state.tradeCount, state.cycleCount, List.copyOf(warnings));
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

    // 백테스트용 합성 전략 — 계좌·PK 없이 타입/종목만 유효한 값으로 채운다(plan()이 ticker만 참조)
    private static Strategy syntheticStrategy(BacktestCommand command) {
        return new Strategy(null, null, Strategy.Type.VR, Strategy.Status.ACTIVE,
                command.ticker(), Strategy.CycleSeedType.NONE);
    }

    // 합성 VR 상세 — 램프 8파라미터는 백테스트 입력으로 받지 않고 운영의 부호 기반 기본값을 그대로 쓴다
    // gMax=initialGradient, poolLimitFloor=initialPoolLimitRate로 두면 gradientAt()/poolLimitRateAt()의 상하한 클램프가
    // 항상 초기값을 돌려준다 — 즉 "램프 없음, 초기값 고정"(운영의 미지정 시 no-op 규칙)이 그대로 재현된다
    private static StrategyVrDetail syntheticVrDetail(BacktestCommand command) {
        int initialGradient = command.vrRecurringAmount() < 0 ? 20 : 10;
        BigDecimal initialPoolLimitRate = command.vrRecurringAmount() > 0 ? new BigDecimal("0.75")
                : command.vrRecurringAmount() == 0 ? new BigDecimal("0.50") : new BigDecimal("0.25");
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

    // VR 루프의 가변 상태 — 롤오버가 8개 값을 한꺼번에 갱신해야 해 record 대신 가변 홀더로 둔다
    private static final class VrState {
        AccountBalance balance;                // 현재 잔고
        BigDecimal principal;                  // 원금 (시드 + 실제 반영된 적립/인출 누계)
        BigDecimal value;                      // 현재 V값
        final LocalDate firstCycleStartDate;   // 전략 최초 사이클 시작일 — 램프 경과 주수 기준(불변)
        LocalDate cycleStartDate;              // 현재 사이클 시작일 — 롤오버 도래 판정 기준
        BigDecimal poolLimit;                  // 이번 사이클 매수 상한
        BigDecimal poolUsed = BigDecimal.ZERO; // 이번 사이클 매수 체결 누계
        int tradeCount;                        // 체결 건수 누계
        int cycleCount = 1;                    // 진행된 사이클 수
        boolean valueHoldWarned;               // V′≤0 보류 경고 중복 방지 플래그

        VrState(BacktestCommand command, StrategyVrDetail detail, LocalDate startDate) {
            this.balance = new AccountBalance(0, null, command.seed());
            this.principal = command.seed();
            this.value = command.vrInitialValue() != null ? command.vrInitialValue() : BigDecimal.ZERO;
            this.firstCycleStartDate = startDate;
            this.cycleStartDate = startDate;
            this.poolLimit = poolLimitOf(command.seed(), detail.poolLimitRateAt(0));
        }

        // 체결 반영 — 잔고·체결건수·이번 사이클 매수 사용액 갱신
        void applyFills(List<Execution> executions) {
            if (executions.isEmpty()) return;
            balance = balance.applyExecutions(executions);
            tradeCount += executions.size();
            poolUsed = poolUsed.add(executions.stream()
                    .filter(e -> e.direction() == BUY)
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
}
