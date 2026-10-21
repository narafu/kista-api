package com.kista.tradingcore.matching.domain.strategy;

import com.kista.shared.kernel.DcaOperator;
import com.kista.shared.kernel.DcaSkipReason;
import com.kista.tradingcore.matching.domain.model.DcaCondition;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import com.kista.tradingcore.matching.domain.model.DcaDecision;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.matching.domain.model.DcaIndicators;
import com.kista.tradingcore.matching.domain.model.DcaRule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// DCA 조건 판정 커널 — 주식(DcaCycleOrderStrategy)·코인 경로 공용 순수 계산
public final class DcaConditions {

    private DcaConditions() {}

    // 조건 1개 평가 결과 — passed=false면 detail이 skip 사유
    private record Evaluation(boolean passed, String detail) {}

    // 전부 통과면 empty. 전일 종가 없음 → 지표 계산 불가 → 미충족 순. referencePrice는 skip 기록용(전일 종가 실패만 null)
    public static Optional<DcaDecision.Skipped> check(DcaConfig config, List<BigDecimal> closes, DcaCycleOrderStrategy.DcaInputs.Holding holding,
                                                      BigDecimal fearGreed, BigDecimal referencePrice) {
        return check(config.conditions(), closes, holding, fearGreed, referencePrice);
    }

    // 규칙 기반 — AVG_PRICE는 원장(ledger) 평단 기준
    public static Optional<DcaDecision.Skipped> check(DcaRule rule, List<BigDecimal> closes, AccountBalance ledger,
                                                      BigDecimal fearGreed, BigDecimal referencePrice) {
        var holding = ledger == null ? null : new DcaCycleOrderStrategy.DcaInputs.Holding(ledger.holdings(), ledger.avgPrice());
        return check(rule.conditions(), closes, holding, fearGreed, referencePrice);
    }

    private static Optional<DcaDecision.Skipped> check(List<DcaCondition> conditions, List<BigDecimal> closes, DcaCycleOrderStrategy.DcaInputs.Holding holding,
                                                       BigDecimal fearGreed, BigDecimal referencePrice) {
        BigDecimal prevClose = closes == null || closes.isEmpty() ? null : closes.getLast();
        if (prevClose == null || prevClose.signum() <= 0) {
            return Optional.of(new DcaDecision.Skipped(DcaSkipReason.DATA_UNAVAILABLE, "전일 종가 조회 실패", null));
        }
        // 모든 조건 지표값을 먼저 계산 — 하나라도 계산 불가면 다른 조건과 무관하게 DATA_UNAVAILABLE
        List<Evaluation> evaluations = new ArrayList<>();
        for (DcaCondition cond : conditions) {
            Optional<Evaluation> e = evaluate(cond, closes, prevClose, holding, fearGreed);
            if (e.isEmpty()) return Optional.of(new DcaDecision.Skipped(DcaSkipReason.DATA_UNAVAILABLE, dataFailure(cond), referencePrice));
            evaluations.add(e.get());
        }
        return evaluations.stream().filter(e -> !e.passed()).findFirst()
                .map(f -> new DcaDecision.Skipped(DcaSkipReason.CONDITION_NOT_MET, f.detail(), referencePrice));
    }

    // 조건 1개 평가 — 지표 계산 불가면 empty
    private static Optional<Evaluation> evaluate(DcaCondition cond, List<BigDecimal> closes, BigDecimal prevClose,
                                                 DcaCycleOrderStrategy.DcaInputs.Holding holding, BigDecimal fearGreed) {
        String close = "전일 종가 " + money(prevClose);
        return switch (cond.indicator()) {
            case SMA -> DcaIndicators.sma(closes, cond.smaPeriod())
                    .map(v -> compare(cond.operator(), prevClose, v, close, "SMA(" + cond.smaPeriod() + ") " + money(v)));
            case RSI -> DcaIndicators.rsiWilder(closes, DcaIndicators.RSI_PERIOD)
                    .map(v -> compare(cond.operator(), v, cond.threshold(), "RSI(" + DcaIndicators.RSI_PERIOD + ") " + money(v), plain(cond.threshold())));
            case AVG_PRICE -> evaluateAvgPrice(cond, prevClose, holding, close);
            case FEAR_GREED -> Optional.ofNullable(fearGreed)
                    .map(v -> compare(cond.operator(), v, cond.threshold(), "공포탐욕지수 " + money(v), plain(cond.threshold())));
        };
    }

    // 보유 0은 통과 처리 — BUY는 첫 매수 허용, SELL은 뒤 단계 NO_HOLDINGS로 보고 (spec 5.2)
    private static Optional<Evaluation> evaluateAvgPrice(DcaCondition cond, BigDecimal prevClose, DcaCycleOrderStrategy.DcaInputs.Holding holding, String close) {
        if (holding == null) return Optional.empty();
        if (holding.quantity().signum() <= 0) return Optional.of(new Evaluation(true, null));
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

    static String money(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP).toPlainString(); }

    static String plain(BigDecimal v) { return v.stripTrailingZeros().toPlainString(); }
}
