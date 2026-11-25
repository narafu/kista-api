package com.kista.tradingcore.trading.domain.model;

import com.kista.shared.kernel.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StrategyValidation·FieldRejectedException 단위 테스트")
class StrategyValidationTest {

    @Test
    @DisplayName("FieldError.of는 귀속 예외의 field, 종목 중복은 ticker, 그 외는 null을 쓰고 문장은 그대로 둔다")
    void fieldError_attribution() {
        assertThat(StrategyValidation.FieldError.of(new FieldRejectedException("gMax", "상한 오류입니다.")))
                .isEqualTo(new StrategyValidation.FieldError("gMax", "상한 오류입니다."));
        assertThat(StrategyValidation.FieldError.of(new DuplicateStrategyTickerException()))
                .isEqualTo(new StrategyValidation.FieldError("ticker", new DuplicateStrategyTickerException().getMessage()));
        assertThat(StrategyValidation.FieldError.of(new IllegalStateException("상태 오류입니다.")))
                .isEqualTo(new StrategyValidation.FieldError(null, "상태 오류입니다."));
    }

    @Test
    @DisplayName("attribute는 IAE를 같은 문장의 귀속 예외로 바꾸고, 이미 귀속된 예외와 다른 예외는 그대로 던진다")
    void attribute_wrapsOnlyUnattributedIae() {
        assertThatThrownBy(() -> FieldRejectedException.attribute("ticker", () -> { throw new IllegalArgumentException("허용되지 않는 값입니다."); }))
                .isInstanceOfSatisfying(FieldRejectedException.class, e -> {
                    assertThat(e.field()).isEqualTo("ticker");
                    assertThat(e.getMessage()).isEqualTo("허용되지 않는 값입니다.");
                });
        assertThatThrownBy(() -> FieldRejectedException.attribute("ticker", () -> { throw new FieldRejectedException("gMax", "x"); }))
                .isInstanceOfSatisfying(FieldRejectedException.class, e -> assertThat(e.field()).isEqualTo("gMax"));
        assertThatThrownBy(() -> FieldRejectedException.attribute("ticker", () -> { throw new IllegalStateException("x"); }))
                .isExactlyInstanceOf(IllegalStateException.class);
        assertThat(FieldRejectedException.attribute("ticker", () -> 7)).isEqualTo(7);
    }

    @Test
    @DisplayName("입력 예수금이 가용 예수금을 넘을 때만 경고하고, 둘 중 하나라도 모르면 경고하지 않는다")
    void depositWarnings_onlyWhenExceeded() {
        assertThat(StrategyValidation.depositWarnings(new BigDecimal("1000.01"), new BigDecimal("1000"), Currency.USD))
                .extracting(StrategyValidation.Warning::code).containsExactly(StrategyValidation.Code.AVAILABLE_CASH_EXCEEDED);
        assertThat(StrategyValidation.depositWarnings(new BigDecimal("1000"), new BigDecimal("1000"), Currency.USD)).isEmpty();
        assertThat(StrategyValidation.depositWarnings(null, new BigDecimal("1000"), Currency.USD)).isEmpty();
        assertThat(StrategyValidation.depositWarnings(new BigDecimal("5000"), null, Currency.USD)).isEmpty();
        // 문장은 계좌 통화 표기를 따른다
        assertThat(StrategyValidation.depositWarnings(new BigDecimal("2000"), new BigDecimal("1234.5"), Currency.USD))
                .extracting(StrategyValidation.Warning::detail).singleElement().asString().contains("가용 예수금 $1,234.50보다");
        assertThat(StrategyValidation.depositWarnings(new BigDecimal("20000"), new BigDecimal("15000"), Currency.KRW))
                .extracting(StrategyValidation.Warning::detail).singleElement().asString().contains("가용 예수금 15,000원보다");
    }
}
