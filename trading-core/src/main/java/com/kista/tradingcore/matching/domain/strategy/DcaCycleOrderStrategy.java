package com.kista.tradingcore.matching.domain.strategy;

import com.kista.shared.kernel.CryptoTradingRules;
import com.kista.shared.kernel.DcaShortfallPolicy;
import com.kista.shared.kernel.DcaSizingType;
import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.Market;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.matching.domain.model.DcaDecision;
import com.kista.tradingcore.matching.domain.model.DcaRule;
import com.kista.tradingcore.matching.domain.model.DcaRuleDecision;
import com.kista.tradingcore.matching.domain.model.InfinitePosition;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import com.kista.tradingcore.matching.domain.model.PrivacyPlan;
import com.kista.tradingcore.matching.domain.model.VrPosition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// DCA 회차 판정 커널 — 순수 계산. 주식은 plan()(입력은 trading DcaPlanInputCollector), 코인은 CryptoDcaRoundExecutor가 규칙마다 decideRule()을 직접 호출한다
public class DcaCycleOrderStrategy implements CycleOrderStrategy {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    // 규칙 단위 판정 입력 — closes: 전일까지 종가(null=조회 실패), ledger: 원장(AVG_PRICE·보유비율 기준),
    // sellableAfterReserved: SELL만(브로커 판매가능수량 − 예약분 − 앞 규칙 계획분, null=조회 실패),
    // currentPrice: 현재가(null=조회 실패), availableCash: 크립토 BUY만(지갑 견적 통화) — 주식은 null(allocator가 판정),
    // tradingRules: 크립토 필수(거래소 수수료·최소 금액·수량 단위), 주식은 null
    public record DcaRuleInputs(DcaRule rule, Market market, List<BigDecimal> closes, AccountBalance ledger,
                                BigDecimal sellableAfterReserved, BigDecimal fearGreed, BigDecimal currentPrice,
                                BigDecimal availableCash, CryptoTradingRules tradingRules) {
        public DcaRuleInputs {
            if (market.isCrypto() && tradingRules == null) throw new IllegalArgumentException("코인 시장은 거래 규칙이 필요합니다");
        }
    }

    @Override
    public StrategyType cycleType() {
        return StrategyType.DCA;
    }

    // 주식 DCA 배치 계획 — 판정할 규칙마다 decideRule, 같은 시점 발동 규칙은 각자 주문(상계 없음)
    // SELL 가용 = 수집 시점 가용(min(판매가능 − 예약분, 원장)) − 같은 배치 앞 규칙이 계획한 SELL — 규칙 합계가 원장·판매가능을 넘지 않게
    // 판정할 규칙이 없어도 빈 계획을 돌려준다(전략 차원 skip 아님). 코인은 CryptoDcaRoundExecutor가 decideRule을 직접 부른다
    @Override
    public Optional<OrderPlan> plan(PlanContext ctx) {
        List<DcaRuleInputs> due = ctx.dca() == null ? List.of() : ctx.dca().dueRules();
        List<PlannedOrder> orders = new ArrayList<>();
        List<DcaRuleDecision.Skipped> skips = new ArrayList<>();
        Map<String, BigDecimal> referencePrices = new LinkedHashMap<>();
        BigDecimal plannedSell = BigDecimal.ZERO;
        for (DcaRuleInputs in : due) {
            DcaRule rule = in.rule();
            boolean sell = rule.direction() == OrderDirection.SELL;
            DcaRuleInputs adjusted = sell && in.sellableAfterReserved() != null
                    ? withSellable(in, in.sellableAfterReserved().subtract(plannedSell).max(BigDecimal.ZERO))
                    : in;
            switch (decideRule(adjusted)) {
                case DcaRuleDecision.Planned p -> {
                    if (sell) plannedSell = plannedSell.add(p.quantity());
                    orders.add(PlannedOrder.of(ctx.tradeDate(), ctx.ticker(), rule.orderType(), rule.direction(),
                            p.quantity().intValueExact(), p.limitPrice(), rule.timing(), rule.orderLeg()));
                    referencePrices.put(rule.orderLeg(), p.referencePrice());
                }
                case DcaRuleDecision.Skipped s -> skips.add(s);
            }
        }
        return Optional.of(new OrderPlan(null, null, List.copyOf(orders), List.copyOf(skips), Map.copyOf(referencePrices)));
    }

    private static DcaRuleInputs withSellable(DcaRuleInputs in, BigDecimal sellable) {
        return new DcaRuleInputs(in.rule(), in.market(), in.closes(), in.ledger(), sellable, in.fearGreed(),
                in.currentPrice(), in.availableCash(), in.tradingRules());
    }

    // 규칙 1개 판정 — 데이터 → 조건 → 보유(SELL) → 규모 → 부족 정책 → 최소 단위
    public DcaRuleDecision decideRule(DcaRuleInputs in) {
        DcaRule rule = in.rule();
        String key = rule.ruleKey();
        List<BigDecimal> closes = in.closes();
        BigDecimal prevClose = closes == null || closes.isEmpty() ? null : closes.getLast();
        if (prevClose == null || prevClose.signum() <= 0) {
            return new DcaRuleDecision.Skipped(key, DcaSkipReason.DATA_UNAVAILABLE, "전일 종가 조회 실패", null);
        }
        boolean sell = rule.direction() == OrderDirection.SELL;
        boolean crypto = in.market().isCrypto();
        // 기준가 — 코인은 현재가(없으면 전일 종가), 주식 SELL은 전일 종가, 주식 BUY는 max(전일 종가, 현재가)
        BigDecimal basis = crypto ? (in.currentPrice() != null && in.currentPrice().signum() > 0 ? in.currentPrice() : prevClose)
                : sell ? prevClose
                : referencePriceForBuy(prevClose, in.currentPrice());
        if (sell && in.ledger() == null) {
            return new DcaRuleDecision.Skipped(key, DcaSkipReason.DATA_UNAVAILABLE, "원장 조회 실패", basis);
        }
        if (sell && in.sellableAfterReserved() == null) {
            return new DcaRuleDecision.Skipped(key, DcaSkipReason.DATA_UNAVAILABLE, "판매가능수량 조회 실패", basis);
        }
        Optional<DcaDecision.Skipped> notMet = DcaConditions.check(rule, closes, in.ledger(), in.fearGreed(), basis);
        if (notMet.isPresent()) {
            DcaDecision.Skipped s = notMet.get();
            return new DcaRuleDecision.Skipped(key, s.reason(), s.detail(), s.referencePrice());
        }
        int scale = crypto ? in.tradingRules().quantityScale() : 0; // 코인 수량은 거래소 수량 단위, 주식 정수
        BigDecimal quantity;
        BigDecimal notional = null;
        if (sell) {
            // 가용 = min(원장 보유, 판매가능 − 예약분)
            BigDecimal available = in.ledger().holdings().min(in.sellableAfterReserved()).setScale(scale, RoundingMode.FLOOR);
            if (available.signum() <= 0) {
                return new DcaRuleDecision.Skipped(key, DcaSkipReason.NO_HOLDINGS, "가용 보유 수량 0", basis);
            }
            BigDecimal want = rule.sizing().type() == DcaSizingType.HOLDING_PERCENT
                    ? in.ledger().holdings().multiply(rule.sizing().percent()).divide(HUNDRED, scale, RoundingMode.FLOOR)
                    : rule.sizing().amount().divide(basis, scale, RoundingMode.FLOOR);
            if (want.compareTo(available) > 0 && rule.shortfall() == DcaShortfallPolicy.REJECT) {
                return new DcaRuleDecision.Skipped(key, DcaSkipReason.INSUFFICIENT_HOLDINGS,
                        "가용 " + DcaConditions.plain(available) + " < 계획 " + DcaConditions.plain(want), basis);
            }
            quantity = want.min(available);
        } else if (crypto) {
            if (in.availableCash() == null) {
                return new DcaRuleDecision.Skipped(key, DcaSkipReason.DATA_UNAVAILABLE, "주문 가능 잔고 조회 실패", basis);
            }
            // 금액은 계좌 통화 소수 자릿수 — 수수료 포함 지출이 지갑을 넘지 않게 가용 금액을 역산
            int amountScale = in.market().amountScale();
            BigDecimal affordable = in.tradingRules().affordableAmount(in.availableCash(), amountScale);
            BigDecimal want = rule.sizing().amount().setScale(amountScale, RoundingMode.FLOOR);
            // 가용 0이면 PARTIAL도 잔고 부족 — 최소 금액 미달로 기록되면 원인(지갑 비어 있음)이 가려진다
            if (want.compareTo(affordable) > 0 && (rule.shortfall() == DcaShortfallPolicy.REJECT || affordable.signum() <= 0)) {
                return new DcaRuleDecision.Skipped(key, DcaSkipReason.INSUFFICIENT_BALANCE,
                        "필요 " + DcaConditions.plain(want) + " > 가용 " + DcaConditions.plain(affordable), basis);
            }
            notional = want.min(affordable);
            quantity = notional.divide(basis, scale, RoundingMode.FLOOR);
        } else {
            quantity = rule.sizing().amount().divide(basis, 0, RoundingMode.FLOOR);
        }
        if (crypto) {
            BigDecimal value = notional != null ? notional : quantity.multiply(basis);
            BigDecimal minNotional = in.tradingRules().minNotional();
            if (value.compareTo(minNotional) < 0) {
                return new DcaRuleDecision.Skipped(key, DcaSkipReason.BELOW_MIN_ORDER_AMOUNT,
                        "주문 금액 " + value.setScale(in.market().amountScale(), RoundingMode.FLOOR).toPlainString()
                                + " < 최소 " + minNotional.toPlainString(), basis);
            }
        } else if (quantity.signum() == 0) {
            return new DcaRuleDecision.Skipped(key, DcaSkipReason.BELOW_ONE_SHARE,
                    "회차 금액 < 기준가 " + DcaConditions.money(basis), basis);
        }
        // 주식 BUY는 캡 지정가(LOO/LOC), 나머지는 기준가 기록용
        BigDecimal limit = !sell && !crypto ? PriceCapPolicy.capFor(basis) : basis;
        return new DcaRuleDecision.Planned(key, rule.direction(), quantity, notional, basis, limit);
    }

    // BUY 기준가 = max(전일 종가, 현재가) — 밤사이·장중 급등 시 캡 초과 미체결 방지. 현재가 없음·0 이하면 전일 종가
    private static BigDecimal referencePriceForBuy(BigDecimal prevClose, BigDecimal currentPrice) {
        return currentPrice != null && currentPrice.compareTo(prevClose) > 0 ? currentPrice : prevClose;
    }

    @Override
    public BigDecimal minRequiredDeposit(BigDecimal price, PrivacyPlan privacyPlan, int divisionCount) {
        return null; // 사이클 재등록 없음
    }

    @Override
    public boolean endsCycleOnLiquidation() { return false; } // 영구 사이클

    @Override
    public boolean needsCapCheck(InfinitePosition position, VrPosition vrPosition) { return false; } // 계획 시점 캡가 고정

    @Override
    public boolean occupiesSeed() { return false; } // 시드 없이 규칙 금액만 매매

    @Override
    public boolean fixesPricesAtPlan() { return true; } // 계획 시점 캡가 고정 — 현재가 기준 재캡 없음

    @Override
    public boolean recordsRounds() { return true; }
}
