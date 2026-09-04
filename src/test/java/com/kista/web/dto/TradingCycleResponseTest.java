package com.kista.web.dto;

import com.kista.strategyconfig.domain.model.Strategy;
import com.kista.strategyconfig.domain.model.StrategyDetail;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyCycleSeedType;

class TradingCycleResponseTest {

    @Test
    void from_strategyDetail_mapsDivisionCountAndHoldings() {
        Strategy strategy = new Strategy(
                UUID.randomUUID(), UUID.randomUUID(), StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        StrategyDetail detail = new StrategyDetail(strategy, new BigDecimal("1000"), LocalDate.now(), 20, false, null, 0, null);

        TradingCycleResponse response = TradingCycleResponse.from(detail);

        assertThat(response.divisionCount()).isEqualTo(20);
        assertThat(response.currentHoldings()).isZero();
    }

    @Test
    void from_strategyDetail_mapsStartDate() {
        Strategy strategy = new Strategy(
                UUID.randomUUID(), UUID.randomUUID(), StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        LocalDate startDate = LocalDate.of(2026, 8, 1);
        StrategyDetail detail = new StrategyDetail(strategy, new BigDecimal("1000"), startDate, 20, false, null, 0, null);

        TradingCycleResponse response = TradingCycleResponse.from(detail);

        assertThat(response.startDate()).isEqualTo(startDate);
    }
}
