package com.kista.trading.application.service;

import com.kista.trading.domain.model.CyclePosition;
import com.kista.trading.domain.model.StrategyCycle;
import com.kista.trading.domain.model.StrategyCycleVrDetail;
import com.kista.trading.domain.model.StrategyVrDetail;
import com.kista.trading.domain.model.VrSummary;
import com.kista.trading.application.port.output.StrategyCycleVrPort;
import com.kista.trading.application.port.output.StrategyVrDetailPort;
import com.kista.trading.application.usecase.VrStrategyDetailUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// VR 전략의 버전 상세·사이클 상세 저장과 요약 조립을 담당 — VrStrategyDetailUseCase 구현체
@Component
@RequiredArgsConstructor
class VrStrategyLifecycle implements VrStrategyDetailUseCase {

    private final StrategyVrDetailPort strategyVrDetailPort;
    private final StrategyCycleVrPort strategyCycleVrPort;

    @Override
    public StrategyVrDetail saveVersionDetail(UUID strategyVersionId, Integer intervalWeeks,
                                       BigDecimal bandWidth, Integer recurringAmount,
                                       int initialGradient, int gGraceWeeks, int gStepWeeks, int gMax,
                                       BigDecimal initialPoolLimitRate, int pGraceWeeks, int pStepWeeks,
                                       BigDecimal poolLimitFloor) {
        int normalizedRecurringAmount = recurringAmount != null ? recurringAmount : 0;
        return strategyVrDetailPort.save(
                new StrategyVrDetail(strategyVersionId, intervalWeeks, bandWidth, normalizedRecurringAmount,
                        initialGradient, gGraceWeeks, gStepWeeks, gMax,
                        initialPoolLimitRate, pGraceWeeks, pStepWeeks, poolLimitFloor));
    }

    @Override
    public StrategyCycleVrDetail saveInitialCycleDetail(UUID cycleId, BigDecimal initialValue, StrategyVrDetail vrDetail) {
        BigDecimal initialV = initialValue != null ? initialValue : BigDecimal.ZERO;
        return strategyCycleVrPort.save(
                new StrategyCycleVrDetail(cycleId, initialV, vrDetail.gradientAt(0), vrDetail.poolLimitRateAt(0)));
    }

    @Override
    public Optional<VrSummary> findSummary(UUID strategyId, Optional<StrategyCycle> latestCycle,
                                                    Optional<CyclePosition> openingPosition,
                                                    Optional<CyclePosition> latestPosition) {
        return strategyVrDetailPort.findActiveByStrategyId(strategyId)
                .flatMap(vrDetail -> latestCycle
                        .flatMap(cycle -> strategyCycleVrPort.findByCycleId(cycle.id())
                                .map(cycleVr -> {
                                    BigDecimal openingPool = openingPosition
                                            .map(CyclePosition::usdDeposit)
                                            .orElseThrow(() -> new IllegalStateException(
                                                    "VR 시작 포지션 없음: cycleId=" + cycle.id()));
                                    BigDecimal currentPool = latestPosition.map(CyclePosition::usdDeposit).orElse(null);
                                    return buildSummary(vrDetail, cycleVr, openingPool, currentPool);
                                })));
    }

    @Override
    public Map<UUID, StrategyVrDetail> findVrDetailsByVersionIds(Collection<UUID> strategyVersionIds) {
        return strategyVrDetailPort.findByStrategyVersionIds(strategyVersionIds);
    }

    @Override
    public Map<UUID, StrategyCycleVrDetail> findCycleVrDetailsByCycleIds(Collection<UUID> cycleIds) {
        return strategyCycleVrPort.findByCycleIds(cycleIds);
    }

    @Override
    public VrSummary buildSummary(StrategyVrDetail vrDetail, StrategyCycleVrDetail cycleVr,
                                          BigDecimal openingPool, BigDecimal currentPool) {
        if (vrDetail == null || cycleVr == null) return null;
        if (openingPool == null) {
            throw new IllegalStateException("VR 시작 포지션 없음: openingPool=null");
        }
        BigDecimal poolLimit = openingPool.multiply(cycleVr.poolLimitRate())
                .setScale(2, RoundingMode.HALF_UP);
        return new VrSummary(
                cycleVr.value(), vrDetail.bandWidth(), vrDetail.intervalWeeks(),
                vrDetail.recurringAmount(), poolLimit, currentPool, cycleVr.poolLimitRate(), cycleVr.gradient(),
                vrDetail.initialGradient(), vrDetail.gGraceWeeks(), vrDetail.gStepWeeks(), vrDetail.gMax(),
                vrDetail.initialPoolLimitRate(), vrDetail.pGraceWeeks(), vrDetail.pStepWeeks(), vrDetail.poolLimitFloor());
    }
}
