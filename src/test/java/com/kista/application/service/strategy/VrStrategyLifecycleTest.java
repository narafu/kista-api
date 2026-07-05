package com.kista.application.service.strategy;

import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.strategy.StrategyCycleVrDetail;
import com.kista.domain.model.strategy.StrategyDetail;
import com.kista.domain.model.strategy.StrategyVrDetail;
import com.kista.domain.port.out.StrategyCycleVrPort;
import com.kista.domain.port.out.StrategyVrDetailPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("VrStrategyLifecycle 단위 테스트")
class VrStrategyLifecycleTest {

    @Mock StrategyVrDetailPort strategyVrDetailPort;
    @Mock StrategyCycleVrPort strategyCycleVrPort;

    @InjectMocks VrStrategyLifecycle vrStrategyLifecycle;

    @Test
    @DisplayName("saveVersionDetail() recurringAmount null이면 0으로 정규화한다")
    void saveVersionDetail_normalizesNullRecurringAmount() {
        UUID versionId = UUID.randomUUID();
        when(strategyVrDetailPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StrategyVrDetail result = vrStrategyLifecycle.saveVersionDetail(
                versionId, 4, new BigDecimal("15.00"), null);

        assertThat(result.strategyVersionId()).isEqualTo(versionId);
        assertThat(result.recurringAmount()).isZero();
        verify(strategyVrDetailPort).save(any(StrategyVrDetail.class));
    }

    @Test
    @DisplayName("saveInitialCycleDetail() poolLimit과 gradient를 VR 정책으로 저장한다")
    void saveInitialCycleDetail_savesCalculatedPoolLimit() {
        UUID cycleId = UUID.randomUUID();
        StrategyVrDetail vrDetail = new StrategyVrDetail(UUID.randomUUID(), 4, new BigDecimal("15.00"), 100);
        when(strategyCycleVrPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StrategyCycleVrDetail result = vrStrategyLifecycle.saveInitialCycleDetail(
                cycleId, new BigDecimal("1000"), new BigDecimal("3000"), vrDetail);

        assertThat(result.strategyCycleId()).isEqualTo(cycleId);
        assertThat(result.poolLimit()).isEqualByComparingTo("750.00");
        assertThat(result.gradient()).isEqualTo(10);
    }

    @Test
    @DisplayName("findSummary() 활성 VR 상세와 최신 사이클 상세를 합산한다")
    void findSummary_combinesActiveDetailAndCycleDetail() {
        UUID strategyId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        StrategyCycle latestCycle = new StrategyCycle(
                cycleId, strategyId, versionId, new BigDecimal("2000"), null, LocalDate.now(), null, null, null);
        StrategyVrDetail vrDetail = new StrategyVrDetail(versionId, 4, new BigDecimal("15.00"), 0);
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                cycleId, new BigDecimal("3000"), 10, new BigDecimal("1000.00"));
        when(strategyVrDetailPort.findActiveByStrategyId(strategyId)).thenReturn(Optional.of(vrDetail));
        when(strategyCycleVrPort.findByCycleId(cycleId)).thenReturn(Optional.of(cycleVr));

        Optional<StrategyDetail.VrSummary> result = vrStrategyLifecycle.findSummary(
                strategyId, Optional.of(latestCycle));

        assertThat(result).isPresent();
        assertThat(result.get().intervalWeeks()).isEqualTo(4);
        assertThat(result.get().poolLimit()).isEqualByComparingTo("1000.00");
        assertThat(result.get().gradient()).isEqualTo(10);
    }
}
