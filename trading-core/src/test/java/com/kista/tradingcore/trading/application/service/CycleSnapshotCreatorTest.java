package com.kista.tradingcore.trading.application.service;
import static com.kista.shared.support.FixedClockTestConfig.FIXED;
import com.kista.tradingcore.trading.domain.creation.VrRampParams;

import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.trading.domain.model.CycleCapitalEvent;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.domain.model.StrategyCycleVrDetail;
import com.kista.tradingcore.trading.domain.model.StrategyVersion;
import com.kista.tradingcore.matching.domain.model.StrategyVrDetail;
import com.kista.tradingcore.trading.application.port.output.CycleCapitalEventPort;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyCycleVrPort;
import com.kista.tradingcore.trading.application.port.output.StrategyVersionPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CycleSnapshotCreatorTest {

    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CyclePositionPort cyclePositionPort;
    @Mock StrategyCycleVrPort strategyCycleVrPort;
    @Mock StrategyVersionPort strategyVersionPort;
    @Mock VrStrategyDetailService vrStrategyDetailService;
    @Mock CycleCapitalEventPort cycleCapitalEventPort;

    @Test
    void createVrCycleAndSnapshot_usesTotalOpeningAssetsForCycleStartAmount() {
        UUID strategyId = UUID.randomUUID();
        UUID strategyVersionId = UUID.randomUUID();
        StrategyCycle savedCycle = StrategyCycle.start(strategyId, strategyVersionId, new BigDecimal("1600.00"), new BigDecimal("1600.00"), LocalDate.of(2026, 1, 6));
        AccountBalance postBalance = new AccountBalance(5, new BigDecimal("100.00"), new BigDecimal("1000.00"));

        when(strategyCyclePort.save(any(StrategyCycle.class))).thenReturn(savedCycle);

        CycleSnapshotCreator creator = new CycleSnapshotCreator(
                strategyCyclePort, cyclePositionPort, strategyCycleVrPort, strategyVersionPort, vrStrategyDetailService, cycleCapitalEventPort, FIXED);
        creator.createVrCycleAndSnapshot(new CycleSnapshotCreator.VrCycleStart(strategyId, strategyVersionId, postBalance,
                new BigDecimal("120.00"), new BigDecimal("900.00"), 10, new BigDecimal("0.50"), LocalDate.now(FIXED)));

        ArgumentCaptor<StrategyCycle> cycleCaptor = ArgumentCaptor.forClass(StrategyCycle.class);
        ArgumentCaptor<CyclePosition> positionCaptor = ArgumentCaptor.forClass(CyclePosition.class);
        verify(strategyCyclePort).save(cycleCaptor.capture());
        verify(cyclePositionPort).save(positionCaptor.capture());

        assertThat(cycleCaptor.getValue().startAmount()).isEqualByComparingTo("1600.00");
        assertThat(cycleCaptor.getValue().startAmount().scale()).isEqualTo(2);
        assertThat(cycleCaptor.getValue().openAmount()).isEqualByComparingTo(cycleCaptor.getValue().startAmount()); // 개장 평가 = 종가 평가 시작 금액
        assertThat(positionCaptor.getValue().deposit()).isEqualByComparingTo("1000.00");
        assertThat(positionCaptor.getValue().holdings()).isEqualTo(new BigDecimal("5"));
    }

    @Test
    void createVrCycleAndSnapshot_roundsFractionalCentStartAmountHalfUp() {
        UUID strategyId = UUID.randomUUID();
        UUID strategyVersionId = UUID.randomUUID();
        StrategyCycle savedCycle = StrategyCycle.start(strategyId, strategyVersionId, new BigDecimal("1600.01"), new BigDecimal("1600.01"), LocalDate.of(2026, 1, 6));
        AccountBalance postBalance = new AccountBalance(1, new BigDecimal("100.00"), new BigDecimal("1000.00"));

        when(strategyCyclePort.save(any(StrategyCycle.class))).thenReturn(savedCycle);

        CycleSnapshotCreator creator = new CycleSnapshotCreator(
                strategyCyclePort, cyclePositionPort, strategyCycleVrPort, strategyVersionPort, vrStrategyDetailService, cycleCapitalEventPort, FIXED);
        creator.createVrCycleAndSnapshot(new CycleSnapshotCreator.VrCycleStart(strategyId, strategyVersionId, postBalance,
                new BigDecimal("600.005"), new BigDecimal("900.00"), 10, new BigDecimal("0.50"), LocalDate.now(FIXED)));

        ArgumentCaptor<StrategyCycle> cycleCaptor = ArgumentCaptor.forClass(StrategyCycle.class);
        verify(strategyCyclePort).save(cycleCaptor.capture());

        assertThat(cycleCaptor.getValue().startAmount()).isEqualByComparingTo("1600.01");
        assertThat(cycleCaptor.getValue().startAmount().scale()).isEqualTo(2);
    }

    @Test
    void createCycleAndSnapshot_opensCycleWithOpenAmountEqualToSeed() {
        UUID strategyId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(strategyCyclePort.save(any(StrategyCycle.class))).thenAnswer(i -> i.getArgument(0));

        CycleSnapshotCreator creator = new CycleSnapshotCreator(
                strategyCyclePort, cyclePositionPort, strategyCycleVrPort, strategyVersionPort, vrStrategyDetailService, cycleCapitalEventPort, FIXED);
        creator.createCycleAndSnapshot(strategyId, versionId, new BigDecimal("1000.00"), new BigDecimal("50.00"));

        ArgumentCaptor<StrategyCycle> cycleCaptor = ArgumentCaptor.forClass(StrategyCycle.class);
        verify(strategyCyclePort).save(cycleCaptor.capture());
        assertThat(cycleCaptor.getValue().startAmount()).isEqualByComparingTo("1000.00");
        assertThat(cycleCaptor.getValue().openAmount()).isEqualByComparingTo("1000.00");
    }

    @Test
    void reconfigureVrCycle_injectionClosesOldCycleBeforeCapitalAndOpensNewCycleAfterCapital() {
        CyclePosition preAdjustment = new CyclePosition(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("500.00"),
                new BigDecimal("95.00"), new BigDecimal("50.00"), BigDecimal.valueOf(10), null, null);
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
                new BigDecimal("95.00"), new BigDecimal("50.00"), BigDecimal.valueOf(10), null, null);
        AccountBalance postAdjustment = new AccountBalance(
                6, new BigDecimal("50.00"), new BigDecimal("350.00"));

        ReconfigureAmounts amounts = reconfigure(preAdjustment, postAdjustment, new BigDecimal("120.00"));

        assertThat(amounts.oldCycleEndAmount()).isEqualByComparingTo("1700.00");
        assertThat(amounts.newCycleStartAmount()).isEqualByComparingTo("1070.00");
    }

    @Test
    void reconfigureVrCycleBeforeStart_updatesFirstCycleInPlaceWithoutNewCycle() {
        UUID strategyId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        UUID positionId = UUID.randomUUID();
        UUID newVersionId = UUID.randomUUID();
        CyclePosition latest = new CyclePosition(
                positionId, cycleId, new BigDecimal("500.00"),
                new BigDecimal("95.00"), new BigDecimal("50.00"), BigDecimal.valueOf(10), null, null);
        AccountBalance postBalance = new AccountBalance(12, new BigDecimal("51.6667"), new BigDecimal("700.00"));
        StrategyVrDetail newDetail = new StrategyVrDetail(
                newVersionId, 4, new BigDecimal("15.00"), 0,
                10, 52, 26, 10, new BigDecimal("0.50"), 52, 26, new BigDecimal("0.50"));
        VrRampParams ramp = new VrRampParams(10, 52, 26, 10, new BigDecimal("0.50"), 52, 26, new BigDecimal("0.50"));

        when(cyclePositionPort.findLatestOne(cycleId)).thenReturn(Optional.of(latest));
        when(strategyVersionPort.nextVersionNo(strategyId)).thenReturn(2);
        when(strategyVersionPort.save(any())).thenReturn(new StrategyVersion(newVersionId, strategyId, 2, null, null));
        when(vrStrategyDetailService.saveVersionDetail(newVersionId, 4, new BigDecimal("15.00"), 0, ramp))
                .thenReturn(newDetail);

        CycleSnapshotCreator creator = new CycleSnapshotCreator(
                strategyCyclePort, cyclePositionPort, strategyCycleVrPort, strategyVersionPort, vrStrategyDetailService, cycleCapitalEventPort, FIXED);
        creator.reconfigureVrCycleBeforeStart(new CycleSnapshotCreator.VrReconfiguration(
                strategyId, cycleId, 4, new BigDecimal("15.00"), 0, ramp,
                postBalance, new BigDecimal("120.00"), new BigDecimal("1000.00"), 0));

        verify(strategyCyclePort, never()).markEnded(any(), any(), any());
        verify(strategyCyclePort, never()).save(any());
        verify(strategyCyclePort).updateStrategyVersion(cycleId, newVersionId);
        // 700 + 12 × 120 = 2140.00 (scale 2)
        ArgumentCaptor<BigDecimal> startAmount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(strategyCyclePort).updateStartAmount(eq(cycleId), startAmount.capture());
        assertThat(startAmount.getValue()).isEqualTo(new BigDecimal("2140.00"));
        verify(strategyCyclePort, never()).save(any()); // openAmount는 재기록하지 않는다
        verify(cyclePositionPort).updateBalance(positionId, postBalance);
        // 원금 차액은 원가 기준 — 예수금 +200, 주식 +2주(입력 단가 60 = 120) → 320.00
        ArgumentCaptor<CycleCapitalEvent> event = ArgumentCaptor.forClass(CycleCapitalEvent.class);
        verify(cycleCapitalEventPort).save(event.capture());
        assertThat(event.getValue().kind()).isEqualTo(CycleCapitalEvent.Kind.RESET);
        assertThat(event.getValue().cyclePositionId()).isEqualTo(positionId);
        assertThat(event.getValue().depositDelta()).isEqualByComparingTo("200.00");
        assertThat(event.getValue().holdingsDelta()).isEqualByComparingTo("2");
        assertThat(event.getValue().principalDelta()).isEqualByComparingTo("320.00");
        ArgumentCaptor<StrategyCycleVrDetail> vr = ArgumentCaptor.forClass(StrategyCycleVrDetail.class);
        verify(strategyCycleVrPort).save(vr.capture());
        assertThat(vr.getValue().strategyCycleId()).isEqualTo(cycleId);
        assertThat(vr.getValue().value()).isEqualByComparingTo("1000.00");
        assertThat(vr.getValue().gradient()).isEqualTo(newDetail.gradientAt(0));
        assertThat(vr.getValue().poolLimitRate()).isEqualByComparingTo(newDetail.poolLimitRateAt(0));
        // nextVersionNo는 활성 버전 소프트삭제보다 먼저 — 순서 뒤집히면 번호 중복(uq 위반)
        InOrder order = inOrder(strategyVersionPort);
        order.verify(strategyVersionPort).nextVersionNo(strategyId);
        order.verify(strategyVersionPort).softDeleteActiveByStrategyId(eq(strategyId), any());
    }

    @Test
    void reconfigureVrCycleBeforeStart_parameterOnly_recordsNoEvent() {
        UUID strategyId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        UUID newVersionId = UUID.randomUUID();
        CyclePosition latest = new CyclePosition(
                UUID.randomUUID(), cycleId, new BigDecimal("500.00"),
                new BigDecimal("95.00"), new BigDecimal("50.00"), BigDecimal.valueOf(10), null, null);
        StrategyVrDetail newDetail = new StrategyVrDetail(
                newVersionId, 4, new BigDecimal("15.00"), 0,
                10, 52, 26, 10, new BigDecimal("0.50"), 52, 26, new BigDecimal("0.50"));
        VrRampParams ramp = new VrRampParams(10, 52, 26, 10, new BigDecimal("0.50"), 52, 26, new BigDecimal("0.50"));
        when(cyclePositionPort.findLatestOne(cycleId)).thenReturn(Optional.of(latest));
        when(strategyVersionPort.nextVersionNo(strategyId)).thenReturn(2);
        when(strategyVersionPort.save(any())).thenReturn(new StrategyVersion(newVersionId, strategyId, 2, null, null));
        when(vrStrategyDetailService.saveVersionDetail(newVersionId, 4, new BigDecimal("15.00"), 0, ramp)).thenReturn(newDetail);

        CycleSnapshotCreator creator = new CycleSnapshotCreator(
                strategyCyclePort, cyclePositionPort, strategyCycleVrPort, strategyVersionPort, vrStrategyDetailService, cycleCapitalEventPort, FIXED);
        creator.reconfigureVrCycleBeforeStart(new CycleSnapshotCreator.VrReconfiguration(
                strategyId, cycleId, 4, new BigDecimal("15.00"), 0, ramp,
                latest.toBalance(), new BigDecimal("120.00"), new BigDecimal("1000.00"), 0));

        verify(cycleCapitalEventPort, never()).save(any());
    }

    private ReconfigureAmounts reconfigure(
            CyclePosition preAdjustment, AccountBalance postAdjustment, BigDecimal closingPrice) {
        UUID strategyId = UUID.randomUUID();
        UUID currentCycleId = preAdjustment.strategyCycleId();
        UUID newVersionId = UUID.randomUUID();
        UUID newCycleId = UUID.randomUUID();
        LocalDate cycleDate = LocalDate.now(FIXED); // 옛 사이클 종료일 == 새 사이클 시작일 == 오늘(KST) 2026-01-06
        StrategyVrDetail newDetail = new StrategyVrDetail(
                newVersionId, 4, new BigDecimal("15.00"), 0,
                10, 52, 26, 10, new BigDecimal("0.50"), 52, 26, new BigDecimal("0.50"));

        when(cyclePositionPort.findLatestOne(currentCycleId)).thenReturn(Optional.of(preAdjustment));
        when(strategyVersionPort.nextVersionNo(strategyId)).thenReturn(2);
        when(strategyVersionPort.save(any())).thenReturn(
                new StrategyVersion(newVersionId, strategyId, 2, null, null));
        VrRampParams ramp = new VrRampParams(10, 52, 26, 10, new BigDecimal("0.50"), 52, 26, new BigDecimal("0.50"));
        when(vrStrategyDetailService.saveVersionDetail(newVersionId, 4, new BigDecimal("15.00"), 0, ramp))
                .thenReturn(newDetail);
        when(strategyCyclePort.save(any())).thenAnswer(invocation -> {
            StrategyCycle cycle = invocation.getArgument(0);
            return new StrategyCycle(newCycleId, cycle.strategyId(), cycle.strategyVersionId(),
                    cycle.startAmount(), cycle.endAmount(), cycle.startDate(), cycle.endDate(), null, null);
        });

        CycleSnapshotCreator creator = new CycleSnapshotCreator(
                strategyCyclePort, cyclePositionPort, strategyCycleVrPort, strategyVersionPort, vrStrategyDetailService, cycleCapitalEventPort, FIXED);
        creator.reconfigureVrCycle(new CycleSnapshotCreator.VrReconfiguration(
                strategyId, currentCycleId, 4, new BigDecimal("15.00"), 0, ramp,
                postAdjustment, closingPrice, new BigDecimal("1000.00"), 0));

        ArgumentCaptor<BigDecimal> endAmountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<StrategyCycle> cycleCaptor = ArgumentCaptor.forClass(StrategyCycle.class);
        verify(strategyCyclePort).markEnded(eq(currentCycleId),
                endAmountCaptor.capture(), eq(cycleDate));
        verify(strategyCyclePort).save(cycleCaptor.capture());
        // 종료일 == 새 시작일 == 2026-01-06 (nextTradeDate=01-07이 아님) — 하루 겹침으로 stats 연속성이 깨지는 회귀 방지
        assertThat(cycleCaptor.getValue().startDate()).isEqualTo(LocalDate.of(2026, 1, 6)).isEqualTo(cycleDate);
        return new ReconfigureAmounts(endAmountCaptor.getValue(), cycleCaptor.getValue().startAmount());
    }

    private record ReconfigureAmounts(BigDecimal oldCycleEndAmount, BigDecimal newCycleStartAmount) {
    }
}
