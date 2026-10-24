package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.trading.application.event.NewCycleStartedEvent;
import com.kista.tradingcore.trading.application.event.InsufficientBalanceEvent;
import com.kista.tradingcore.trading.domain.model.TradingAccount;
import com.kista.tradingcore.privacy.domain.model.PrivacyTradeBase;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.domain.model.StrategyInfiniteDetail;
import com.kista.tradingcore.trading.domain.model.StrategyVersion;
import com.kista.tradingcore.trading.domain.model.TradingUserProfile;
import com.kista.tradingcore.trading.application.port.output.*;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyDefaults;

// 사이클 종료(holdings==0) 시 CycleSeedType 정책에 따라 새 StrategyCycle + 시작 스냅샷 생성
// NONE → 전략 PAUSED / MAX → 내부 원장 / MAINTAIN → min(시작 시드, 직전 사이클 최종 원장) — 증권사 잔고 조회 없음
// package-private — application/service 패키지 전용
@Service
@RequiredArgsConstructor
@Slf4j
class CycleRotationService {

    private final StrategyPort strategyPort;                   // 시스템 자동 일시중지(사이클 재등록 실패)에도 사용
    private final StrategyVersionPort strategyVersionPort;     // 활성 전략 버전 조회/종료
    private final StrategyInfiniteDetailPort strategyInfiniteDetailPort;
    private final CyclePositionPort cyclePositionPort;         // MAX 시드 계산용 최신 포지션 조회 (읽기 전용)
    private final CycleSnapshotCreator cycleSnapshotCreator;   // StrategyCycle + CyclePosition 원자적 저장
    private final ApplicationEventPublisher eventPublisher;    // 새 사이클 시작 이벤트 발행 (재등록 완료) + 관리자 알림 이벤트 (잔고 부족·오류)
    private final CycleOrderStrategies cycleStrategies;        // 전략 타입별 최소금액 정책

    void rotate(Strategy strategy, StrategyCycle currentCycle, TradingAccount account, TradingUserProfile userProfile,
                BigDecimal price, PrivacyTradeBase privacyTradeBase) {

        if (strategy.cycleSeedType() == StrategyCycleSeedType.NONE) {
            // NONE → 전략 PAUSED (연속 없음)
            strategyPort.pause(strategy.id());
            log.info("[strategyId={}] 사이클 종료 (NONE) → PAUSED", strategy.id());
            return;
        }

        // 시드는 전략 자신의 원장(마지막 CyclePosition.usdDeposit)만으로 정한다 — 원장 이상으로 키울 수 없으므로 부족 분기가 없다
        BigDecimal maxSeed = calcLastPositionDeposit(strategy, currentCycle);
        BigDecimal targetSeed = strategy.cycleSeedType() == StrategyCycleSeedType.MAX
                ? maxSeed
                : currentCycle.startAmount().min(maxSeed); // MAINTAIN: 손실 뒤엔 남은 돈으로, 수익 뒤엔 원래 시드로

        // 원장이 0 이하(소진·음수)면 0/음수 시드 사이클을 열지 않고 NONE과 같이 PAUSED — 관리자 알림은 최소금액 미달과 같은 이벤트
        if (targetSeed.signum() <= 0) {
            strategyPort.pause(strategy.id());
            log.warn("[strategyId={}] 원장 소진({}) — 새 사이클 없이 PAUSED", strategy.id(), targetSeed);
            eventPublisher.publishEvent(new InsufficientBalanceEvent(null, account.id(), account.nickname(),
                    0, targetSeed, strategy.ticker(), null, 0, false));
            return;
        }

        BigDecimal minRequired;
        try {
            // 최소금액 가드 — 전략 타입별 정책은 전략 객체에 위임. 미달이어도 재등록 자체는 차단하지 않고
            // 정보성 알림만 발행한다(예전엔 여기서 재등록을 취소했으나, 그러면 새 사이클 없이 종료 사이클만 남는
            // "좀비 사이클" 상태가 되어 BatchContextFactory가 매 배치마다 오류 알림을 반복 발행했다) — cycleSeedType대로
            // 그대로 등록해 축소된 시드로라도 매매가 이어지게 하고, 부족 여부는 알림으로만 알린다
            int divisionCount = strategyInfiniteDetailPort.findActiveByStrategyId(strategy.id())
                    .map(StrategyInfiniteDetail::divisionCount)
                    .orElse(StrategyDefaults.DEFAULT_DIVISION_COUNT);
            minRequired = cycleStrategies.of(strategy.type()).minRequiredDeposit(
                    price, privacyTradeBase == null ? null : privacyTradeBase.toPlan(), divisionCount);

            // 새 StrategyCycle + 시작 스냅샷 원자적 생성 (시드 결정 방식 stamp)
            StrategyVersion activeVersion = strategyVersionPort.findActiveByStrategyId(strategy.id())
                    .orElseThrow(() -> new IllegalStateException("활성 전략 버전이 없습니다: " + strategy.id()));
            cycleSnapshotCreator.createCycleAndSnapshot(strategy.id(), activeVersion.id(), targetSeed, price);
        } catch (RuntimeException e) {
            // 재등록 실패 시에도 markEnded는 이미 커밋된 상태 — PAUSED 없이 던지면 전략은 ACTIVE인 채
            // 후속 사이클 없는 좀비로 남는다. 원래 예외는 그대로 전파해 TradingBatchGuard가 기존대로 알림·격리한다
            // (알림 이벤트 발행은 이 try 밖 — 사이클 생성 성공 뒤 리스너 예외로 건강한 전략이 PAUSED되지 않도록)
            strategyPort.pause(strategy.id());
            log.error("[strategyId={}] 사이클 재등록 실패 → PAUSED (좀비 사이클 방지)", strategy.id(), e);
            throw e;
        }
        log.info("[strategyId={}] 사이클 재등록 완료: {} → targetSeed={}", strategy.id(), strategy.cycleSeedType(), targetSeed);

        if (minRequired != null && targetSeed.compareTo(minRequired) < 0) {
            log.warn("[strategyId={}] 최소금액 미달 — 축소된 시드로 재등록 진행: {} < {}", strategy.id(), targetSeed, minRequired);
            eventPublisher.publishEvent(new InsufficientBalanceEvent(null, account.id(), account.nickname(),
                    0, targetSeed, strategy.ticker(), null, 0, false));
        }
        eventPublisher.publishEvent(new NewCycleStartedEvent(userProfile.userId(), account.id(), account.nickname(),
                strategy.type(), strategy.ticker(), targetSeed)); // 사용자 알림 이벤트
    }

    // 마지막 CyclePosition의 usdDeposit = MAX 시드의 내부 원장 기준
    private BigDecimal calcLastPositionDeposit(Strategy strategy, StrategyCycle currentCycle) {
        return cyclePositionPort.findLatestOneByStrategyId(strategy.id())
                .map(CyclePosition::usdDeposit)
                .orElse(currentCycle.startAmount()); // fallback: 현재 사이클 시드
    }
}
