package com.kista.tradingcore.matching.domain.strategy;

import com.kista.shared.kernel.DcaOperator;
import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.matching.domain.model.DcaCondition;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import com.kista.tradingcore.matching.domain.model.DcaDecision;
import com.kista.tradingcore.matching.domain.model.DcaIndicators;
import com.kista.tradingcore.matching.domain.model.InfinitePosition;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import com.kista.tradingcore.matching.domain.model.PrivacyPlan;
import com.kista.tradingcore.matching.domain.model.VrPosition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// DCA 회차 판정 커널 — 순수 계산. 배치 진입은 trading DcaRoundPlanner가 decide()를 직접 호출한다
public class DcaCycleOrderStrategy implements CycleOrderStrategy {

    // DCA 판정 입력 — closes: 조회 US 세션 이전 확정 종가 오름차순(null=조회 실패), sellableQuantity: SELL만(예약분 차감 후),
    // holding: AVG_PRICE 조건이 있을 때만 브로커 실잔고(null=조회 실패·미조회), fearGreed: FEAR_GREED 조건이 있을 때만 직전 개장일 CNN 지수(null=조회 실패·미조회),
    // currentPrice: 배치 시작 시점 현재가(개장 배치=프리마켓, 마감 배치=장중, null=조회 실패) — BUY 기준가 산정용
    public record DcaInputs(DcaConfig config, StrategyTicker ticker, LocalDate tradeDate,
                            List<BigDecimal> closes, Integer sellableQuantity, Holding holding, BigDecimal fearGreed,
                            BigDecimal currentPrice) {
        public record Holding(int quantity, BigDecimal avgPrice) {} // 계좌+티커 합산 보유
    }

    // 조건 1개 평가 결과 — passed=false면 detail이 skip 사유
    private record Evaluation(boolean passed, String detail) {}

    @Override
    public StrategyType cycleType() {
        return StrategyType.DCA;
    }

    // DCA는 PlanContext 경로를 쓰지 않는다 — 판정 사유(skip)를 돌려줘야 해서 DcaRoundPlanner → decide()로 진입
    @Override
    public Optional<OrderPlan> plan(PlanContext ctx) {
        throw new UnsupportedOperationException("DCA는 DcaRoundPlanner.decide 경유로만 계획한다");
    }

    // 회차 판정 — 데이터 → 조건 → 수량 → 잔고 순 (spec 5.2, skip 우선순위 9장)
    public DcaDecision decide(DcaInputs in) {
        List<BigDecimal> closes = in.closes();
        BigDecimal prevClose = closes == null || closes.isEmpty() ? null : closes.getLast();
        if (prevClose == null || prevClose.signum() <= 0) {
            return new DcaDecision.Skipped(DcaSkipReason.DATA_UNAVAILABLE, "전일 종가 조회 실패", null);
        }
        DcaConfig c = in.config();
        boolean sell = c.direction() == OrderDirection.SELL;
        // 주문 기준가 — 수량·지정가·이력 기록 공용. 조건 평가는 전일 종가 그대로
        BigDecimal basis = sell ? prevClose : referencePriceForBuy(prevClose, in.currentPrice());
        // skip 우선순위(spec 9장): DATA_UNAVAILABLE → CONDITION_NOT_MET → NO_HOLDINGS(SELL) → BELOW_ONE_SHARE
        if (sell && in.sellableQuantity() == null) {
            return new DcaDecision.Skipped(DcaSkipReason.DATA_UNAVAILABLE, "판매가능수량 조회 실패", basis);
        }
        // 모든 조건 지표값을 먼저 계산 — 하나라도 계산 불가면 다른 조건과 무관하게 DATA_UNAVAILABLE
        List<Evaluation> evaluations = new ArrayList<>();
        for (DcaCondition cond : c.conditions()) {
            Optional<Evaluation> e = evaluate(cond, closes, prevClose, in);
            if (e.isEmpty()) return new DcaDecision.Skipped(DcaSkipReason.DATA_UNAVAILABLE, dataFailure(cond), basis);
            evaluations.add(e.get());
        }
        Optional<Evaluation> failed = evaluations.stream().filter(e -> !e.passed()).findFirst();
        if (failed.isPresent()) return new DcaDecision.Skipped(DcaSkipReason.CONDITION_NOT_MET, failed.get().detail(), basis);
        if (sell && in.sellableQuantity() <= 0) {
            return new DcaDecision.Skipped(DcaSkipReason.NO_HOLDINGS, "판매가능수량 0주", basis);
        }
        int quantity = c.amountUsd().divide(basis, 0, RoundingMode.FLOOR).intValueExact();
        if (quantity == 0) {
            return new DcaDecision.Skipped(DcaSkipReason.BELOW_ONE_SHARE,
                    "회차 금액 " + plain(c.amountUsd()) + " < 기준가 " + money(basis), basis);
        }
        if (sell) quantity = Math.min(quantity, in.sellableQuantity()); // 보유 부족 → 보유 전량
        // BUY는 캡 지정가(LOO/LOC로 접수), SELL은 시장가라 가격은 기준가 기록용
        BigDecimal price = sell ? basis : PriceCapPolicy.capFor(basis);
        PlannedOrder order = PlannedOrder.of(in.tradeDate(), in.ticker(), c.orderType(), c.direction(),
                quantity, price, c.timing(), c.orderLeg());
        return new DcaDecision.Planned(order, basis);
    }

    // BUY 기준가 = max(전일 종가, 현재가) — 밤사이·장중 급등 시 캡 초과 미체결 방지. 현재가 없음·0 이하면 전일 종가
    private static BigDecimal referencePriceForBuy(BigDecimal prevClose, BigDecimal currentPrice) {
        return currentPrice != null && currentPrice.compareTo(prevClose) > 0 ? currentPrice : prevClose;
    }

    // 조건 1개 평가 — 지표 계산 불가면 empty
    private static Optional<Evaluation> evaluate(DcaCondition cond, List<BigDecimal> closes, BigDecimal prevClose, DcaInputs in) {
        String close = "전일 종가 " + money(prevClose);
        return switch (cond.indicator()) {
            case SMA -> DcaIndicators.sma(closes, cond.smaPeriod())
                    .map(v -> compare(cond.operator(), prevClose, v, close, "SMA(" + cond.smaPeriod() + ") " + money(v)));
            case RSI -> DcaIndicators.rsiWilder(closes, DcaIndicators.RSI_PERIOD)
                    .map(v -> compare(cond.operator(), v, cond.threshold(), "RSI(" + DcaIndicators.RSI_PERIOD + ") " + money(v), plain(cond.threshold())));
            case AVG_PRICE -> evaluateAvgPrice(cond, prevClose, in.holding(), close);
            case FEAR_GREED -> Optional.ofNullable(in.fearGreed())
                    .map(v -> compare(cond.operator(), v, cond.threshold(), "공포탐욕지수 " + money(v), plain(cond.threshold())));
        };
    }

    // 보유 0주는 통과 처리 — BUY는 첫 매수 허용, SELL은 뒤 단계 NO_HOLDINGS로 보고 (spec 5.2)
    private static Optional<Evaluation> evaluateAvgPrice(DcaCondition cond, BigDecimal prevClose, DcaInputs.Holding holding, String close) {
        if (holding == null) return Optional.empty();
        if (holding.quantity() <= 0) return Optional.of(new Evaluation(true, null));
        if (holding.avgPrice() == null || holding.avgPrice().signum() <= 0) return Optional.empty();
        BigDecimal n = cond.threshold();
        BigDecimal target = holding.avgPrice().multiply(BigDecimal.ONE.add(n.movePointLeft(2))).setScale(2, RoundingMode.HALF_UP);
        String sign = n.signum() < 0 ? "−" : "+";
        String rhs = "평단 " + money(holding.avgPrice()) + " × (1 " + sign + " " + plain(n.abs()) + "%) = " + money(target);
        return Optional.of(compare(cond.operator(), prevClose, target, close, rhs));
    }

    private static Evaluation compare(DcaOperator op, BigDecimal left, BigDecimal right, String leftLabel, String rightLabel) {
        boolean passed = op.test(left, right);
        return new Evaluation(passed, passed ? null : leftLabel + " " + negatedSymbol(op) + " " + rightLabel);
    }

    // 미충족 표시용 — 조건의 반대 관계
    private static String negatedSymbol(DcaOperator op) {
        return switch (op) {
            case LTE -> ">";
            case LT -> "≥";
            case GTE -> "<";
            case GT -> "≤";
        };
    }

    private static String dataFailure(DcaCondition cond) {
        return switch (cond.indicator()) {
            case SMA -> "SMA(" + cond.smaPeriod() + ") 계산용 일봉 부족";
            case RSI -> "RSI(" + DcaIndicators.RSI_PERIOD + ") 계산용 일봉 부족";
            case AVG_PRICE -> "보유 평단 조회 실패";
            case FEAR_GREED -> "공포탐욕지수 조회 실패";
        };
    }

    private static String money(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP).toPlainString(); }

    private static String plain(BigDecimal v) { return v.stripTrailingZeros().toPlainString(); }

    @Override
    public BigDecimal minRequiredDeposit(BigDecimal price, PrivacyPlan privacyPlan, int divisionCount) {
        return null; // 사이클 재등록 없음
    }

    @Override
    public boolean endsCycleOnLiquidation() { return false; } // 영구 사이클

    @Override
    public boolean needsCapCheck(InfinitePosition position, VrPosition vrPosition) { return false; } // 계획 시점 캡가 고정

    @Override
    public int allocationPriority() { return 200; } // 기존 전략 예산 우선 보장

    @Override
    public boolean snapshotsBrokerBalance() { return true; }

    // 예산 부족 시 단일 BUY 수량을 floor(budget / 캡가)로 축소 — 브로커가 지정가×수량으로 주문가능금액을 검증하므로 캡가 기준
    @Override
    public List<PlannedOrder> fitBuysToBudget(List<PlannedOrder> buyOrders, BigDecimal budget) {
        return buyOrders.stream()
                .map(o -> o.withQuantity(budget.divide(o.price(), 0, RoundingMode.FLOOR).intValue()))
                .filter(o -> o.quantity() > 0)
                .toList();
    }
}
