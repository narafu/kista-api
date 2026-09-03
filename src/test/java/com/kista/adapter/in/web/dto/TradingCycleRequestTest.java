package com.kista.adapter.in.web.dto;

import com.kista.domain.model.strategy.Strategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import com.kista.sharedkernel.StrategyType;

class TradingCycleRequestTest {

    @Test
    void omittedDivisionCountMapsToCommandSentinel() {
        TradingCycleRequest request = new TradingCycleRequest(
                StrategyType.INFINITE, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null);

        assertThat(request.toRegisterCommand().divisionCount()).isZero();
    }

    @Test
    void scheduledStartDateIsPassedThroughToRegisterCommand() {
        LocalDate scheduledStartDate = LocalDate.of(2026, 8, 1);
        TradingCycleRequest request = new TradingCycleRequest(
                StrategyType.INFINITE, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                scheduledStartDate, null);

        assertThat(request.toRegisterCommand().scheduledStartDate()).isEqualTo(scheduledStartDate);
    }

    @Test
    void initialVrValueIsPassedThroughToRegisterCommand() {
        TradingCycleRequest request = new TradingCycleRequest(
                StrategyType.VR, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, new BigDecimal("5000.00"));

        assertThat(request.toRegisterCommand().initialVrValue()).isEqualByComparingTo("5000.00");
    }
}
