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
// public — application/service/trading의 CycleSnapshotCreator(VR 운영 중 재설정)에서도 재사용하므로 예외적 공개 접근자
@Component
@RequiredArgsConstructor
public class VrStrategyLifecycle {

    private final StrategyVrDetailPort strategyVrDetailPort;
    private final StrategyCycleVrPort strategyCycleVrPort;

    // 램프 8필드는 호출측(StrategyService)이 이미 null 정규화를 마친 값이라고 가정한다
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

    // 등록 시점(경과 0주) 스냅샷 — gradientAt(0)/poolLimitRateAt(0)은 각각 initialGradient/initialPoolLimitRate와 동일
    // poolLimit은 더 이상 달러로 저장하지 않고 비율(poolLimitRate)만 저장 — 달러 파생은 조회 시점(buildSummary/CycleOrderComputer)에 수행
    StrategyCycleVrDetail saveInitialCycleDetail(UUID cycleId, BigDecimal initialUsdDeposit,
                                                 BigDecimal initialValue, StrategyVrDetail vrDetail) {
        BigDecimal initialV = initialValue != null ? initialValue : BigDecimal.ZERO;
        return strategyCycleVrPort.save(
                new StrategyCycleVrDetail(cycleId, initialV, vrDetail.gradientAt(0), vrDetail.poolLimitRateAt(0)));
    }

    Optional<StrategyDetail.VrSummary> findSummary(UUID strategyId, Optional<StrategyCycle> latestCycle) {
        return strategyVrDetailPort.findActiveByStrategyId(strategyId)
                .flatMap(vrDetail -> latestCycle
                        .flatMap(cycle -> strategyCycleVrPort.findByCycleId(cycle.id())
                                .map(cycleVr -> buildSummary(vrDetail, cycleVr, cycle.startAmount()))));
    }

    // startAmount: 조회 대상 사이클의 시작 시드 — poolLimit 달러 파생(startAmount × poolLimitRate)에 사용
    StrategyDetail.VrSummary buildSummary(StrategyVrDetail vrDetail, StrategyCycleVrDetail cycleVr, BigDecimal startAmount) {
        if (vrDetail == null || cycleVr == null) return null;
        BigDecimal poolLimit = startAmount != null
                ? startAmount.multiply(cycleVr.poolLimitRate()).setScale(2, RoundingMode.HALF_UP)
                : null;
        return new StrategyDetail.VrSummary(
                cycleVr.value(), vrDetail.bandWidth(), vrDetail.intervalWeeks(),
                vrDetail.recurringAmount(), poolLimit, cycleVr.poolLimitRate(), cycleVr.gradient(),
                vrDetail.initialGradient(), vrDetail.gGraceWeeks(), vrDetail.gStepWeeks(), vrDetail.gMax(),
                vrDetail.initialPoolLimitRate(), vrDetail.pGraceWeeks(), vrDetail.pStepWeeks(), vrDetail.poolLimitFloor());
    }
}
