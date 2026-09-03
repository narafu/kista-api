package com.kista.trading.application.service;

import com.kista.application.service.strategy.VrStrategyLifecycle;
import com.kista.trading.domain.model.AccountBalance;
import com.kista.trading.domain.model.CyclePosition;
import com.kista.trading.domain.model.StrategyCycle;
import com.kista.domain.model.strategy.StrategyVersion;
import com.kista.domain.model.strategy.StrategyVrDetail;
import com.kista.trading.domain.port.out.CyclePositionPort;
import com.kista.trading.domain.port.out.StrategyCyclePort;
import com.kista.trading.domain.port.out.StrategyCycleVrPort;
import com.kista.domain.port.out.StrategyVersionPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

    @Test
    void reconfigureVrCycle_injectionClosesOldCycleBeforeCapitalAndOpensNewCycleAfterCapital() {
        CyclePosition preAdjustment = new CyclePosition(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("500.00"),
                new BigDecimal("95.00"), new BigDecimal("50.00"), 10, null, null);
        AccountBalance postAdjustment = new AccountBalance(
                12, new BigDecimal("51.6667"), new BigDecimal("700.00"));

        ReconfigureAmounts amounts = reconfigure(preAdjustment, postAdjustment, new BigDecimal("120.00"));

        assertThat(amounts.oldCycleEndAmount()).isEqualByComparingTo("1700.00");
        assertThat(amounts.newCycleStartAmount()).isEqualByComparingTo("2140.00");
    }

    @Test
    void reconfigureVrCycle_withdrawalClosesOldCycleBeforeCapitalAndOpensNewCycleAfterCapital() {
        CyclePosition preAdjustment = new CyclePosition(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("500.00"),
                new BigDecimal("95.00"), new BigDecimal("50.00"), 10, null, null);
        AccountBalance postAdjustment = new AccountBalance(
                6, new BigDecimal("50.00"), new BigDecimal("350.00"));

        ReconfigureAmounts amounts = reconfigure(preAdjustment, postAdjustment, new BigDecimal("120.00"));

        assertThat(amounts.oldCycleEndAmount()).isEqualByComparingTo("1700.00");
        assertThat(amounts.newCycleStartAmount()).isEqualByComparingTo("1070.00");
    }

    private ReconfigureAmounts reconfigure(
            CyclePosition preAdjustment, AccountBalance postAdjustment, BigDecimal closingPrice) {
        UUID strategyId = UUID.randomUUID();
        UUID currentCycleId = preAdjustment.strategyCycleId();
        UUID newVersionId = UUID.randomUUID();
        UUID newCycleId = UUID.randomUUID();
        LocalDate today = LocalDate.of(2026, 7, 29);
        StrategyVrDetail newDetail = new StrategyVrDetail(
                newVersionId, 4, new BigDecimal("15.00"), 0,
                10, 52, 26, 10, new BigDecimal("0.50"), 52, 26, new BigDecimal("0.50"));

        when(cyclePositionPort.findLatestOne(currentCycleId)).thenReturn(Optional.of(preAdjustment));
        when(strategyVersionPort.nextVersionNo(strategyId)).thenReturn(2);
        when(strategyVersionPort.save(any())).thenReturn(
                new StrategyVersion(newVersionId, strategyId, 2, null, null));
        when(vrStrategyLifecycle.saveVersionDetail(
                newVersionId, 4, new BigDecimal("15.00"), 0,
                10, 52, 26, 10, new BigDecimal("0.50"), 52, 26, new BigDecimal("0.50")))
                .thenReturn(newDetail);
        when(strategyCyclePort.save(any())).thenAnswer(invocation -> {
            StrategyCycle cycle = invocation.getArgument(0);
            return new StrategyCycle(newCycleId, cycle.strategyId(), cycle.strategyVersionId(),
                    cycle.startAmount(), cycle.endAmount(), cycle.startDate(), cycle.endDate(), null, null);
        });

        CycleSnapshotCreator creator = new CycleSnapshotCreator(
                strategyCyclePort, cyclePositionPort, strategyCycleVrPort, strategyVersionPort, vrStrategyLifecycle);
        creator.reconfigureVrCycle(
                strategyId, currentCycleId, today,
                4, new BigDecimal("15.00"), 0,
                10, 52, 26, 10,
                new BigDecimal("0.50"), 52, 26, new BigDecimal("0.50"),
                postAdjustment, closingPrice, new BigDecimal("1000.00"), 0);

        ArgumentCaptor<BigDecimal> endAmountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<StrategyCycle> cycleCaptor = ArgumentCaptor.forClass(StrategyCycle.class);
        verify(strategyCyclePort).markEnded(org.mockito.ArgumentMatchers.eq(currentCycleId),
                endAmountCaptor.capture(), org.mockito.ArgumentMatchers.eq(today));
        verify(strategyCyclePort).save(cycleCaptor.capture());
        return new ReconfigureAmounts(endAmountCaptor.getValue(), cycleCaptor.getValue().startAmount());
    }

    private record ReconfigureAmounts(BigDecimal oldCycleEndAmount, BigDecimal newCycleStartAmount) {
    }
}
