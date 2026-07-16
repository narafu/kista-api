package com.kista.adapter.in.web.dto;

import com.kista.domain.model.strategy.Strategy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TradingCycleRequestTest {

    @Test
    void omittedDivisionCountMapsToCommandSentinel() {
        TradingCycleRequest request = new TradingCycleRequest(
                Strategy.Type.INFINITE, null, null, null, null,
                null, null, null, null);

        assertThat(request.toRegisterCommand().divisionCount()).isZero();
    }
}
