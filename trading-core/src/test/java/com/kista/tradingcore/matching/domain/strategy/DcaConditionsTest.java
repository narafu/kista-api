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
}
