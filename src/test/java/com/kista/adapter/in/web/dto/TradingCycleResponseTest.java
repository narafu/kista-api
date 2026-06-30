package com.kista.adapter.in.web.dto;

import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyDetail;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TradingCycleResponseTest {

    @Test
    void from_strategyDetail_mapsDivisionCountAndHoldings() {
        Strategy strategy = new Strategy(
                UUID.randomUUID(), UUID.randomUUID(), Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE);
        StrategyDetail detail = new StrategyDetail(strategy, new BigDecimal("1000"), 20, false, null, 0);

        TradingCycleResponse response = TradingCycleResponse.from(detail);

        assertThat(response.divisionCount()).isEqualTo(20);
        assertThat(response.currentHoldings()).isZero();
    }
}
