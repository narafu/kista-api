package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyDetail;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyCycleSeedType;

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
