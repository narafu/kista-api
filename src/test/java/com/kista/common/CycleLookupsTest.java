package com.kista.common;

import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.port.out.StrategyCyclePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.SAME_THREAD)
class CycleLookupsTest {

    @Mock
    private StrategyCyclePort strategyCyclePort;

    // 활성 사이클 조회 시 참조할 전략 ID
    private static final UUID STRATEGY_ID = UUID.randomUUID();

    // 활성 사이클 존재 시 사용할 샘플 사이클
    private static final StrategyCycle CYCLE = new StrategyCycle(
            UUID.randomUUID(), STRATEGY_ID, UUID.randomUUID(),
            BigDecimal.valueOf(1000), null,
            LocalDate.of(2026, 7, 1), null,
            null, null
    );

    @Test
    @DisplayName("활성 사이클이 존재하면 해당 사이클을 반환한다")
    void returnsCycleWhenPresent() {
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(CYCLE));

        StrategyCycle result = CycleLookups.requireLatestCycle(strategyCyclePort, STRATEGY_ID);

        assertThat(result).isEqualTo(CYCLE);
    }

    @Test
    @DisplayName("활성 사이클이 없으면 strategyId를 포함한 IllegalStateException을 던진다")
    void throwsWhenAbsent() {
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> CycleLookups.requireLatestCycle(strategyCyclePort, STRATEGY_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("활성 사이클 없음")
                .hasMessageContaining(STRATEGY_ID.toString());
    }
}
