package com.kista.admin.domain.model;

import com.kista.sharedkernel.StrategyType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminStrategySummaryTest {

    @Test
    void strategyId와_strategyType을_그대로_보관한다() {
        UUID strategyId = UUID.randomUUID();
        AdminStrategySummary summary = new AdminStrategySummary(strategyId, StrategyType.INFINITE);

        assertThat(summary.strategyId()).isEqualTo(strategyId);
        assertThat(summary.strategyType()).isEqualTo(StrategyType.INFINITE);
    }
}
