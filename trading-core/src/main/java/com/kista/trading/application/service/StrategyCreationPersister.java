package com.kista.trading.application.service;

import com.kista.matching.domain.model.StrategyVrDetail;
import com.kista.sharedkernel.StrategyCycleSeedType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;
import com.kista.trading.application.port.output.*;
import com.kista.trading.application.usecase.VrStrategyDetailUseCase;
import com.kista.trading.domain.model.*;
import com.kista.trading.domain.strategy.VrRampParams;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// 신규 전략 저장 5단계(strategy·version·detail·cycle·position)의 트랜잭션 경계 —
// StrategyCreationService.register는 증권사 호출 때문에 NOT_SUPPORTED라, 저장만 이 빈의 @Transactional 하나로 묶는다.
// 같은 클래스 내부 호출은 프록시를 우회하므로(self-invocation) 별도 빈으로 분리했다.
@Component
@RequiredArgsConstructor
class StrategyCreationPersister {

    private final StrategyPort strategyPort;
    private final StrategyVersionPort strategyVersionPort;
    private final StrategyInfiniteDetailPort strategyInfiniteDetailPort;
    private final VrStrategyDetailUseCase vrStrategyLifecycle;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final CyclePositionInfiniteDetailPort cyclePositionInfiniteDetailPort;

    // 저장 결과 — VR이 아니면 vrDetail·cycleVr는 null
    record Persisted(Strategy strategy, StrategyVrDetail vrDetail, StrategyCycle cycle,
                     CyclePosition initialPosition, StrategyCycleVrDetail cycleVr) {}

    // ramp: VR 등록일 때만 non-null, vrValue: VR V값(override 반영) — 비VR은 null
    @Transactional
    Persisted persist(UUID accountId, StrategyType type, StrategyTicker ticker, StrategyCycleSeedType seedType,
                      int divisionCount, Integer intervalWeeks, BigDecimal bandWidth, Integer recurringAmount,
                      VrRampParams ramp, BigDecimal initialUsdDeposit, int initialHoldings, BigDecimal initialAvgPrice,
                      BigDecimal marketPrice, BigDecimal initialStockValue, BigDecimal vrValue, LocalDate scheduledStart) {
        // strategy → strategy_versions → 전략 타입별 detail 순 저장
        Strategy saved = strategyPort.save(new Strategy(null, accountId, type, StrategyStatus.ACTIVE, ticker, seedType));
        StrategyVersion version = strategyVersionPort.save(
                new StrategyVersion(null, saved.id(), strategyVersionPort.nextVersionNo(saved.id()), null, null)
        );
        StrategyVrDetail vrDetail = null;
        if (saved.isInfinite()) {
            strategyInfiniteDetailPort.save(new StrategyInfiniteDetail(version.id(), divisionCount));
        } else if (saved.isVr()) {
            vrDetail = vrStrategyLifecycle.saveVersionDetail(version.id(), intervalWeeks, bandWidth, recurringAmount,
                    ramp.initialGradient(), ramp.gGraceWeeks(), ramp.gStepWeeks(), ramp.gMax(),
                    ramp.initialPoolLimitRate(), ramp.pGraceWeeks(), ramp.pStepWeeks(), ramp.poolLimitFloor());
        }

        // strategy_cycles → cycle_positions → 전략 타입별 cycle_detail 순 저장
        // startAmount = 현금 + 시장가×보유수량 — VR도 총 시작자산을 동일하게 보존한다(vrValue override와 무관)
        BigDecimal normalizedInitialUsdDeposit = initialUsdDeposit != null ? initialUsdDeposit : BigDecimal.ZERO;
        BigDecimal startAmount = normalizedInitialUsdDeposit.add(initialStockValue);
        StrategyCycle cycle = strategyCyclePort.save(StrategyCycle.start(saved.id(), version.id(), startAmount, scheduledStart));

        CyclePosition initialPosition = initialHoldings > 0
                ? cyclePositionPort.save(CyclePosition.bootstrapSnapshot(
                        cycle.id(), normalizedInitialUsdDeposit, initialHoldings, initialAvgPrice, marketPrice))
                : cyclePositionPort.save(CyclePosition.initialSnapshot(cycle.id(), normalizedInitialUsdDeposit));

        StrategyCycleVrDetail cycleVr = null;
        if (saved.isInfinite()) {
            cyclePositionInfiniteDetailPort.save(new CyclePositionInfiniteDetail(initialPosition.id(), false));
        } else if (saved.isVr()) {
            cycleVr = vrStrategyLifecycle.saveInitialCycleDetail(cycle.id(), vrValue, vrDetail);
        }
        return new Persisted(saved, vrDetail, cycle, initialPosition, cycleVr);
    }
}
