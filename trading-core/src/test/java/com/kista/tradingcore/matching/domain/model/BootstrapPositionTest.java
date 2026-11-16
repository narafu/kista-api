package com.kista.tradingcore.matching.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

// 중간부터 시작 입력 검증 — 주식 정수 주 경로
class BootstrapPositionTest {

    @Test
    void 주식_정수_보유는_그대로_통과한다() {
        assertThat(BootstrapPosition.validate(new BigDecimal("10"), new BigDecimal("40"))).isEqualByComparingTo("10");
    }

    @Test
    void 주식_소수_보유는_거부한다() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> BootstrapPosition.validate(new BigDecimal("0.5"), new BigDecimal("40")))
                .withMessage("주식 보유 수량은 정수여야 합니다.");
    }

    @Test
    void 주식_보유가_int_범위를_넘으면_거부한다() {
        BigDecimal overMax = BigDecimal.valueOf(Integer.MAX_VALUE).add(BigDecimal.ONE);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> BootstrapPosition.validate(overMax, new BigDecimal("40")))
                .withMessage("주식 보유 수량이 허용 범위를 넘었습니다.");
    }
}
