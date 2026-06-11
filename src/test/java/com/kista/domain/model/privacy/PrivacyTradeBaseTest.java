package com.kista.domain.model.privacy;

import com.kista.domain.model.strategy.Strategy.Ticker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PrivacyTradeBase / PrivacyCurrentBase 불변식 검증")
class PrivacyTradeBaseTest {

    @Test
    @DisplayName("PrivacyTradeBase: currentCycleStart=null이면 생성 실패")
    void privacyTradeBase_nullCurrentCycleStart_throws() {
        assertThatThrownBy(() -> new PrivacyTradeBase(UUID.randomUUID(), new BigDecimal("20.00"), 10, null, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("currentCycleStart 이상");
    }

    @Test
    @DisplayName("PrivacyTradeBase: currentCycleStart<=0이면 생성 실패")
    void privacyTradeBase_nonPositiveCurrentCycleStart_throws() {
        assertThatThrownBy(() -> new PrivacyTradeBase(UUID.randomUUID(), new BigDecimal("20.00"), 10, BigDecimal.ZERO, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("currentCycleStart 이상");
    }

    @Test
    @DisplayName("PrivacyCurrentBase: currentCycleStart=null이면 생성 실패")
    void privacyCurrentBase_nullCurrentCycleStart_throws() {
        assertThatThrownBy(() -> new PrivacyCurrentBase(Ticker.SOXL, null, LocalDate.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("currentCycleStart 이상");
    }

    @Test
    @DisplayName("PrivacyCurrentBase: currentCycleStart<=0이면 생성 실패")
    void privacyCurrentBase_nonPositiveCurrentCycleStart_throws() {
        assertThatThrownBy(() -> new PrivacyCurrentBase(Ticker.SOXL, new BigDecimal("-1"), LocalDate.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("currentCycleStart 이상");
    }
}
