package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.matching.domain.model.DcaStrategyConfig;
import com.kista.tradingcore.matching.domain.model.StrategyVrDetail;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.trading.application.port.output.*;
import com.kista.tradingcore.trading.application.usecase.VrStrategyDetailUseCase;
import com.kista.tradingcore.trading.domain.model.*;
import com.kista.tradingcore.trading.domain.creation.StrategyCreationResolver.ResolvedCreation;
import com.kista.tradingcore.trading.domain.creation.VrRampParams;
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
    private final StrategyDcaDetailPort dcaDetailPort;

    // 저장 입력 — 같은 타입 인자(BigDecimal 7)의 자리 바뀜을 막으려고 묶는다
    // resolved: 런타임 정책이 해석한 ticker·divisionCount·VR 주기/밴드/적립액, ramp: VR 등록일 때만 non-null,
    // vrValue: VR V값(override 반영) — 비VR은 null, dca: DCA 등록일 때만 non-null
    record Draft(UUID accountId, StrategyType type, StrategyCycleSeedType seedType, ResolvedCreation resolved,
                 VrRampParams ramp, BigDecimal initialUsdDeposit, BigDecimal initialHoldings, BigDecimal initialAvgPrice,
                 BigDecimal marketPrice, BigDecimal startAmount, BigDecimal vrValue, LocalDate scheduledStart,
                 DcaStrategyConfig dca) {}

    @Transactional
    Strategy persist(Draft d) {
        ResolvedCreation resolved = d.resolved();
        // strategy → strategy_versions → 전략 타입별 detail 순 저장
        Strategy saved = strategyPort.save(new Strategy(null, d.accountId(), d.type(), StrategyStatus.ACTIVE, resolved.ticker(), d.seedType()));
        StrategyVersion version = strategyVersionPort.save(
                new StrategyVersion(null, saved.id(), strategyVersionPort.nextVersionNo(saved.id()), null, null)
        );
        StrategyVrDetail vrDetail = null;
        if (saved.isInfinite()) {
            strategyInfiniteDetailPort.save(new StrategyInfiniteDetail(version.id(), resolved.divisionCount()));
        } else if (saved.isVr()) {
            vrDetail = vrStrategyLifecycle.saveVersionDetail(
                    version.id(), resolved.intervalWeeks(), resolved.bandWidth(), resolved.recurringAmount(), d.ramp());
        } else if (saved.isDca()) {
            dcaDetailPort.save(version.id(), d.dca()); // DCA 버전 규칙 (ruleKey는 호출부가 확정)
        }

        // strategy_cycles → cycle_positions → 전략 타입별 cycle_detail 순 저장
        // startAmount 산출 규칙은 호출부(StrategyCreationService) 소유
        BigDecimal normalizedInitialUsdDeposit = d.initialUsdDeposit() != null ? d.initialUsdDeposit() : BigDecimal.ZERO;
        StrategyCycle cycle = strategyCyclePort.save(StrategyCycle.start(saved.id(), version.id(), d.startAmount(), d.scheduledStart()));

        // DCA는 marketPrice 없음(closingPrice null) — 시작 원장 = 초기 보유·평단, 예수금 0
        CyclePosition initialPosition = d.initialHoldings().signum() > 0
                ? cyclePositionPort.save(CyclePosition.bootstrapSnapshot(
                        cycle.id(), normalizedInitialUsdDeposit, d.initialHoldings(), d.initialAvgPrice(), d.marketPrice()))
                : cyclePositionPort.save(CyclePosition.initialSnapshot(cycle.id(), normalizedInitialUsdDeposit));

        if (saved.isInfinite()) {
            cyclePositionInfiniteDetailPort.save(new CyclePositionInfiniteDetail(initialPosition.id(), false));
        } else if (saved.isVr()) {
            vrStrategyLifecycle.saveInitialCycleDetail(cycle.id(), d.vrValue(), vrDetail);
        }
        return saved;
    }
}
