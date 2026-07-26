package com.kista.adapter.in.web.dto;

import com.kista.domain.model.strategy.Strategy;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class TradingCycleRequestTest {

    @Test
    void omittedDivisionCountMapsToCommandSentinel() {
        TradingCycleRequest request = new TradingCycleRequest(
                Strategy.Type.INFINITE, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null);

        assertThat(request.toRegisterCommand().divisionCount()).isZero();
    }

    @Test
    void scheduledStartDateIsPassedThroughToRegisterCommand() {
        LocalDate scheduledStartDate = LocalDate.of(2026, 8, 1);
        TradingCycleRequest request = new TradingCycleRequest(
                Strategy.Type.INFINITE, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                scheduledStartDate);

        assertThat(request.toRegisterCommand().scheduledStartDate()).isEqualTo(scheduledStartDate);
    }
}
