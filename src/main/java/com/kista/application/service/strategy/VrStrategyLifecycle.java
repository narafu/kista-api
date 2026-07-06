package com.kista.application.service.strategy;

import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.strategy.StrategyCycleVrDetail;
import com.kista.domain.model.strategy.StrategyDetail;
import com.kista.domain.model.strategy.StrategyVrDetail;
import com.kista.domain.port.out.StrategyCycleVrPort;
import com.kista.domain.port.out.StrategyVrDetailPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;

// VR 전략의 버전 상세·사이클 상세 저장과 요약 조립을 담당
@Component
@RequiredArgsConstructor
class VrStrategyLifecycle {

    private final StrategyVrDetailPort strategyVrDetailPort;
    private final StrategyCycleVrPort strategyCycleVrPort;

    StrategyVrDetail saveVersionDetail(UUID strategyVersionId, Integer intervalWeeks,
                                       BigDecimal bandWidth, Integer recurringAmount) {
        int normalizedRecurringAmount = recurringAmount != null ? recurringAmount : 0;
        return strategyVrDetailPort.save(
                new StrategyVrDetail(strategyVersionId, intervalWeeks, bandWidth, normalizedRecurringAmount));
    }

    StrategyCycleVrDetail saveInitialCycleDetail(UUID cycleId, BigDecimal initialUsdDeposit,
                                                 BigDecimal initialValue, StrategyVrDetail vrDetail) {
        BigDecimal initialPool = initialUsdDeposit != null ? initialUsdDeposit : BigDecimal.ZERO;
        BigDecimal initialV = initialValue != null ? initialValue : BigDecimal.ZERO;
        BigDecimal initialAssets = initialPool.add(initialV);
        BigDecimal poolLimit = initialAssets
                .multiply(vrDetail.poolLimitRate())
                .setScale(2, RoundingMode.HALF_UP);
        return strategyCycleVrPort.save(
                new StrategyCycleVrDetail(cycleId, initialV, vrDetail.gradient(), poolLimit));
    }

    Optional<StrategyDetail.VrSummary> findSummary(UUID strategyId, Optional<StrategyCycle> latestCycle) {
        return strategyVrDetailPort.findActiveByStrategyId(strategyId)
                .flatMap(vrDetail -> latestCycle
                        .flatMap(cycle -> strategyCycleVrPort.findByCycleId(cycle.id()))
                        .map(cycleVr -> buildSummary(vrDetail, cycleVr)));
    }

    StrategyDetail.VrSummary buildSummary(StrategyVrDetail vrDetail, StrategyCycleVrDetail cycleVr) {
        if (vrDetail == null || cycleVr == null) return null;
        return new StrategyDetail.VrSummary(
                cycleVr.value(), vrDetail.bandWidth(), vrDetail.intervalWeeks(),
                vrDetail.recurringAmount(), cycleVr.poolLimit(), cycleVr.gradient());
    }
}
