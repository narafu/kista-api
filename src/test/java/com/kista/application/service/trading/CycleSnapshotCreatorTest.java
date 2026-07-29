package com.kista.application.service.trading;

import com.kista.application.service.strategy.VrStrategyLifecycle;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyCycleVrPort;
import com.kista.domain.port.out.StrategyVersionPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CycleSnapshotCreatorTest {

    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CyclePositionPort cyclePositionPort;
    @Mock StrategyCycleVrPort strategyCycleVrPort;
    @Mock StrategyVersionPort strategyVersionPort;
    @Mock VrStrategyLifecycle vrStrategyLifecycle;

    @Test
    void createVrCycleAndSnapshot_usesTotalOpeningAssetsForCycleStartAmount() {
        UUID strategyId = UUID.randomUUID();
        UUID strategyVersionId = UUID.randomUUID();
        StrategyCycle savedCycle = StrategyCycle.start(strategyId, strategyVersionId, new BigDecimal("1600.00"));
        AccountBalance postBalance = new AccountBalance(5, new BigDecimal("100.00"), new BigDecimal("1000.00"));

        when(strategyCyclePort.save(org.mockito.ArgumentMatchers.any(StrategyCycle.class))).thenReturn(savedCycle);

        CycleSnapshotCreator creator = new CycleSnapshotCreator(
                strategyCyclePort, cyclePositionPort, strategyCycleVrPort, strategyVersionPort, vrStrategyLifecycle);
        creator.createVrCycleAndSnapshot(strategyId, strategyVersionId, postBalance, new BigDecimal("120.00"),
                new BigDecimal("900.00"), 10, new BigDecimal("0.50"));

        ArgumentCaptor<StrategyCycle> cycleCaptor = ArgumentCaptor.forClass(StrategyCycle.class);
        ArgumentCaptor<CyclePosition> positionCaptor = ArgumentCaptor.forClass(CyclePosition.class);
        verify(strategyCyclePort).save(cycleCaptor.capture());
        verify(cyclePositionPort).save(positionCaptor.capture());

        assertThat(cycleCaptor.getValue().startAmount()).isEqualByComparingTo("1600.00");
        assertThat(cycleCaptor.getValue().startAmount().scale()).isEqualTo(2);
        assertThat(positionCaptor.getValue().usdDeposit()).isEqualByComparingTo("1000.00");
        assertThat(positionCaptor.getValue().holdings()).isEqualTo(5);
    }

    @Test
    void createVrCycleAndSnapshot_roundsFractionalCentStartAmountHalfUp() {
        UUID strategyId = UUID.randomUUID();
        UUID strategyVersionId = UUID.randomUUID();
        StrategyCycle savedCycle = StrategyCycle.start(strategyId, strategyVersionId, new BigDecimal("1600.01"));
        AccountBalance postBalance = new AccountBalance(1, new BigDecimal("100.00"), new BigDecimal("1000.00"));

        when(strategyCyclePort.save(org.mockito.ArgumentMatchers.any(StrategyCycle.class))).thenReturn(savedCycle);

        CycleSnapshotCreator creator = new CycleSnapshotCreator(
                strategyCyclePort, cyclePositionPort, strategyCycleVrPort, strategyVersionPort, vrStrategyLifecycle);
        creator.createVrCycleAndSnapshot(strategyId, strategyVersionId, postBalance, new BigDecimal("600.005"),
                new BigDecimal("900.00"), 10, new BigDecimal("0.50"));

        ArgumentCaptor<StrategyCycle> cycleCaptor = ArgumentCaptor.forClass(StrategyCycle.class);
        verify(strategyCyclePort).save(cycleCaptor.capture());

        assertThat(cycleCaptor.getValue().startAmount()).isEqualByComparingTo("1600.01");
        assertThat(cycleCaptor.getValue().startAmount().scale()).isEqualTo(2);
    }
}
