package com.kista.tradingcore.matching.domain.strategy;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.DcaOperator;
import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.tradingcore.matching.domain.model.DcaCondition;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import com.kista.tradingcore.matching.domain.model.DcaDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class DcaConditionsTest {

    private static DcaConfig config(DcaCondition... conditions) {
        return new DcaConfig(OrderDirection.BUY, new BigDecimal("300"), OrderType.MOC, DcaFrequency.DAILY, null, null, List.of(conditions));
    }

    private static List<BigDecimal> closes(String... v) {
        return java.util.Arrays.stream(v).map(BigDecimal::new).toList();
    }

    private static Optional<DcaDecision.Skipped> check(DcaConfig c, List<BigDecimal> closes, DcaCycleOrderStrategy.DcaInputs.Holding h) {
        BigDecimal ref = closes == null || closes.isEmpty() ? null : closes.getLast();
        return DcaConditions.check(c, closes, h, null, ref);
    }

    @Test
    void noConditionsPasses() {
        assertThat(check(config(), closes("100"), null)).isEmpty();
    }

    @Test
    void missingPrevCloseIsDataUnavailableWithNullReference() {
        Optional<DcaDecision.Skipped> r = DcaConditions.check(config(), null, null, null, new BigDecimal("1"));
        assertThat(r).hasValueSatisfying(s -> {
            assertThat(s.reason()).isEqualTo(DcaSkipReason.DATA_UNAVAILABLE);
            assertThat(s.detail()).isEqualTo("전일 종가 조회 실패");
            assertThat(s.referencePrice()).isNull();
        });
    }

    @Test
    void avgPriceWithFractionalHoldingEvaluates() {
        DcaConfig c = config(new DcaCondition(DcaIndicator.AVG_PRICE, DcaOperator.LTE, null, new BigDecimal("-5")));
        var holding = new DcaCycleOrderStrategy.DcaInputs.Holding(new BigDecimal("0.00012345"), new BigDecimal("1000"));
        assertThat(check(c, closes("950"), holding)).isEmpty();
        assertThat(check(c, closes("951"), holding)).hasValueSatisfying(s -> {
            assertThat(s.reason()).isEqualTo(DcaSkipReason.CONDITION_NOT_MET);
            assertThat(s.detail()).isEqualTo("전일 종가 951.00 > 평단 1000.00 × (1 − 5%) = 950.00");
        });
    }

    @Test
    void zeroHoldingPassesAvgPrice() {
        DcaConfig c = config(new DcaCondition(DcaIndicator.AVG_PRICE, DcaOperator.LTE, null, new BigDecimal("-5")));
        assertThat(check(c, closes("951"), new DcaCycleOrderStrategy.DcaInputs.Holding(BigDecimal.ZERO, null))).isEmpty();
    }

    @Test
    void smaShortHistoryIsDataUnavailable() {
        DcaConfig c = config(new DcaCondition(DcaIndicator.SMA, DcaOperator.GTE, 200, null));
        List<BigDecimal> shortCloses = IntStream.range(0, 199).mapToObj(i -> new BigDecimal("100")).toList();
        assertThat(check(c, shortCloses, null)).hasValueSatisfying(s -> {
            assertThat(s.reason()).isEqualTo(DcaSkipReason.DATA_UNAVAILABLE);
            assertThat(s.detail()).isEqualTo("SMA(200) 계산용 일봉 부족");
        });
    }

    @Test
    void skipKeepsReferencePriceUnchanged() {
        DcaConfig c = config(new DcaCondition(DcaIndicator.AVG_PRICE, DcaOperator.LTE, null, new BigDecimal("-5")));
        var holding = new DcaCycleOrderStrategy.DcaInputs.Holding(BigDecimal.ONE, new BigDecimal("1000"));
        BigDecimal ref = new BigDecimal("123.45");
        // 조건 미충족 skip
        assertThat(DcaConditions.check(c, closes("951"), holding, null, ref))
                .hasValueSatisfying(s -> assertThat(s.referencePrice()).isSameAs(ref));
        // 지표 계산 불가 skip
        assertThat(DcaConditions.check(c, closes("951"), null, null, ref))
                .hasValueSatisfying(s -> assertThat(s.referencePrice()).isSameAs(ref));
    }

    @Test
    void fearGreedConditionBoundary() {
        DcaConfig c = config(new DcaCondition(DcaIndicator.FEAR_GREED, DcaOperator.LTE, null, new BigDecimal("25")));
        assertThat(DcaConditions.check(c, closes("100"), null, new BigDecimal("25"), new BigDecimal("100"))).isEmpty();
        assertThat(DcaConditions.check(c, closes("100"), null, new BigDecimal("26"), new BigDecimal("100"))).hasValueSatisfying(s -> {
            assertThat(s.reason()).isEqualTo(DcaSkipReason.CONDITION_NOT_MET);
            assertThat(s.detail()).isEqualTo("공포탐욕지수 26.00 > 25");
        });
        assertThat(DcaConditions.check(c, closes("100"), null, null, new BigDecimal("100"))).hasValueSatisfying(s -> {
            assertThat(s.reason()).isEqualTo(DcaSkipReason.DATA_UNAVAILABLE);
            assertThat(s.detail()).isEqualTo("공포탐욕지수 조회 실패");
        });
    }

    @Test
    void rsiCondition() {
        DcaConfig c = config(new DcaCondition(DcaIndicator.RSI, DcaOperator.LTE, null, new BigDecimal("30")));
        // 15개 연속 하락 → 상승 없음 RSI 0 → 통과
        List<BigDecimal> falling = IntStream.range(0, 15).mapToObj(i -> new BigDecimal(200 - i)).toList();
        assertThat(check(c, falling, null)).isEmpty();
        // 15개 연속 상승 → 하락 없음 RSI 100 → 미충족
        List<BigDecimal> rising = IntStream.range(0, 15).mapToObj(i -> new BigDecimal(100 + i)).toList();
        assertThat(check(c, rising, null)).hasValueSatisfying(s -> {
            assertThat(s.reason()).isEqualTo(DcaSkipReason.CONDITION_NOT_MET);
            assertThat(s.detail()).isEqualTo("RSI(14) 100.00 > 30");
        });
        // 일봉 14개(<15) → 계산 불가
        assertThat(check(c, falling.subList(0, 14), null)).hasValueSatisfying(s ->
                assertThat(s.reason()).isEqualTo(DcaSkipReason.DATA_UNAVAILABLE));
    }
}
