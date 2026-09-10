package com.kista.trading.application.port.output;

import com.kista.trading.domain.model.StrategyCycle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

// StrategyCyclePort.requireLatestByStrategyId default 메서드 검증 — 구 CycleLookupsTest 이관.
// 순수 Mockito mock은 default 메서드를 override해 본문을 실행하지 않으므로(docs/agents/testing.md
// "Mockito + interface default 메서드 주의"), CALLS_REAL_METHODS로 default 본문이 실제 실행되게 한다.
@Execution(ExecutionMode.SAME_THREAD)
class StrategyCyclePortTest {

    private final StrategyCyclePort strategyCyclePort =
            mock(StrategyCyclePort.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));

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

        StrategyCycle result = strategyCyclePort.requireLatestByStrategyId(STRATEGY_ID);

        assertThat(result).isEqualTo(CYCLE);
    }

    @Test
    @DisplayName("활성 사이클이 없으면 strategyId를 포함한 IllegalStateException을 던진다")
    void throwsWhenAbsent() {
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> strategyCyclePort.requireLatestByStrategyId(STRATEGY_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("활성 사이클 없음")
                .hasMessageContaining(STRATEGY_ID.toString());
    }
}
