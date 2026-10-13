package com.kista.tradingcore.matching.domain.model;

import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.DcaOperator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DcaConditionTest {

    private static DcaCondition of(DcaIndicator i, Integer sma, String threshold) {
        return new DcaCondition(i, DcaOperator.LTE, sma, threshold == null ? null : new BigDecimal(threshold));
    }

    @Test
    void valid_conditions() {
        assertThatCode(() -> of(DcaIndicator.SMA, 200, null)).doesNotThrowAnyException();
        assertThatCode(() -> of(DcaIndicator.RSI, null, "35")).doesNotThrowAnyException();
        assertThatCode(() -> of(DcaIndicator.AVG_PRICE, null, "-5.5")).doesNotThrowAnyException();
        assertThatCode(() -> of(DcaIndicator.FEAR_GREED, null, "25")).doesNotThrowAnyException(); // 도메인은 허용, 단계 ② 차단은 API 경계
    }

    @Test
    void sma_requires_supported_period_and_no_threshold() {
        assertThatThrownBy(() -> of(DcaIndicator.SMA, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(DcaIndicator.SMA, 60, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(DcaIndicator.SMA, 20, "1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void non_sma_requires_threshold_and_no_period() {
        assertThatThrownBy(() -> of(DcaIndicator.RSI, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(DcaIndicator.RSI, 20, "30")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ranges() {
        assertThatThrownBy(() -> of(DcaIndicator.RSI, null, "100.01")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(DcaIndicator.RSI, null, "-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(DcaIndicator.AVG_PRICE, null, "-100")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(DcaIndicator.AVG_PRICE, null, "100000000")).isInstanceOf(IllegalArgumentException.class); // numeric(10,2) 정수부 8자리 초과
        assertThatThrownBy(() -> of(DcaIndicator.RSI, null, "30.123")).isInstanceOf(IllegalArgumentException.class); // 소수 3자리
        assertThatCode(() -> of(DcaIndicator.RSI, null, "30.100")).doesNotThrowAnyException(); // trailing zero는 허용
    }

    @Test
    void null_indicator_or_operator_is_iae_not_npe() {
        assertThatThrownBy(() -> new DcaCondition(null, DcaOperator.LT, null, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DcaCondition(DcaIndicator.RSI, null, null, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
    }
}
