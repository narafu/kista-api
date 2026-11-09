package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.trading.application.event.CycleCompletedEvent;
import com.kista.tradingcore.trading.application.event.CycleRotationFailedEvent;
import com.kista.tradingcore.trading.application.event.NewCycleStartedEvent;
import com.kista.tradingcore.trading.application.event.InsufficientBalanceEvent;
import com.kista.tradingcore.trading.application.event.StrategyAutoPausedEvent;
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

import java.util.NoSuchElementException;
import java.math.BigDecimal;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyDefaults;

// 사이클 종료(holdings==0) 시 CycleSeedType 정책에 따라 새 StrategyCycle + 시작 스냅샷 생성
// NONE → 전략 PAUSED / MAX → 내부 원장 / MAINTAIN → min(시작 시드, 직전 사이클 최종 원장) — 증권사 잔고 조회 없음
// 완료 이벤트(CycleCompletedEvent) 발행도 여기서 — 어떤 리스너 예외든 PAUSE 보장 try 안에서 나가야 좀비(ACTIVE+종료 사이클)가 안 생긴다
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

        BigDecimal minRequired;
        BigDecimal targetSeed; // try 밖(생성 성공 뒤 알림)에서도 쓴다
        boolean pausedByPolicy = false; // NONE·1주 미만으로 이미 PAUSE한 뒤의 실패(알림 리스너 등)는 재등록 실패가 아니다
        try {
            // 완료 이벤트는 맨 앞 — NewCycleStartedEvent보다 먼저 나가야 알림 순서가 유지된다
            eventPublisher.publishEvent(new CycleCompletedEvent(userProfile.userId(), account.id(), account.nickname(),
                    strategy.type(), strategy.ticker(), strategy.cycleSeedType()));

            if (strategy.cycleSeedType() == StrategyCycleSeedType.NONE) {
                // NONE → 전략 PAUSED (연속 없음)
                strategyPort.pause(strategy.id());
                pausedByPolicy = true;
                log.info("[strategyId={}] 사이클 종료 (NONE) → PAUSED", strategy.id());
                return;
            }

            // 시드는 전략 자신의 원장(마지막 CyclePosition.deposit)만으로 정한다 — 원장 이상으로 키울 수 없으므로 부족 분기가 없다
            BigDecimal maxSeed = calcLastPositionDeposit(strategy, currentCycle);
            targetSeed = strategy.cycleSeedType() == StrategyCycleSeedType.MAX
                    ? maxSeed
                    : currentCycle.startAmount().min(maxSeed); // MAINTAIN: 손실 뒤엔 남은 돈으로, 수익 뒤엔 원래 시드로

            // 1주도 못 사는 시드는 0회차 매수가 원장 상한에 막혀 교착 — 새 사이클 없이 PAUSED (가격 모르면 0 이하만 판정)
            // 관리자 알림은 최소금액 미달과 같은 이벤트, 사용자에게는 자동 일시정지 알림
            boolean belowOneShare = price != null ? targetSeed.compareTo(price) < 0 : targetSeed.signum() <= 0;
            if (belowOneShare) {
                strategyPort.pause(strategy.id());
                pausedByPolicy = true;
                log.warn("[strategyId={}] 원장 1주 미만({} < {}) — 새 사이클 없이 PAUSED", strategy.id(), targetSeed, price);
                eventPublisher.publishEvent(InsufficientBalanceEvent.adminSeedShortage(account.id(), account.nickname(), targetSeed, strategy.ticker()));
                eventPublisher.publishEvent(new StrategyAutoPausedEvent(userProfile.userId(), account.id(), account.nickname(),
                        strategy.type(), strategy.ticker(), targetSeed, price));
                return;
            }

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
                    .orElseThrow(() -> new NoSuchElementException("활성 전략 버전이 없습니다: " + strategy.id()));
            cycleSnapshotCreator.createCycleAndSnapshot(strategy.id(), activeVersion.id(), targetSeed, price);
        } catch (RuntimeException e) {
            // 정책으로 이미 PAUSE된 뒤의 실패(1주 미만 알림 리스너 등)는 그대로 전파만 한다 — 재정지·재등록 실패 알림 불필요
            if (pausedByPolicy) throw e;
            // 생성 전후 어느 지점이든 실패하면 markEnded는 이미 커밋된 상태 — PAUSED 없이 던지면 전략은 ACTIVE인 채
            // 후속 사이클 없는 좀비로 남는다. PAUSED + 종료 사이클은 StrategyService.reopenCycleIfEnded가 재개 시 처리한다.
            // 원래 예외는 그대로 전파해 TradingBatchGuard가 기존대로 관리자 알림·격리한다
            // (생성 성공 뒤 알림(최소금액 미달·새 사이클 시작)은 이 try 밖 — 리스너 예외로 건강한 전략이 PAUSED되지 않도록)
            log.error("[strategyId={}] 사이클 재등록 실패 → PAUSED 시도 (좀비 사이클 방지)", strategy.id(), e);
            try {
                strategyPort.pause(strategy.id());
            } catch (RuntimeException pauseFailure) {
                e.addSuppressed(pauseFailure); // PAUSE마저 실패해도 원래 원인을 잃지 않는다 — 좀비는 BatchContextFactory가 보고
                throw e;
            }
            // 사용자에게 정지 사실과 재개 방법을 알린다 — 알림 실패가 원래 원인을 가리지 않게 suppressed로만 남긴다
            try {
                eventPublisher.publishEvent(new CycleRotationFailedEvent(userProfile.userId(), account.id(), account.nickname(),
                        strategy.type(), strategy.ticker()));
            } catch (RuntimeException notifyFailure) {
                e.addSuppressed(notifyFailure);
            }
            throw e;
        }
        log.info("[strategyId={}] 사이클 재등록 완료: {} → targetSeed={}", strategy.id(), strategy.cycleSeedType(), targetSeed);

        if (minRequired != null && targetSeed.compareTo(minRequired) < 0) {
            log.warn("[strategyId={}] 최소금액 미달 — 축소된 시드로 재등록 진행: {} < {}", strategy.id(), targetSeed, minRequired);
            eventPublisher.publishEvent(InsufficientBalanceEvent.adminSeedShortage(account.id(), account.nickname(), targetSeed, strategy.ticker()));
        }
        eventPublisher.publishEvent(new NewCycleStartedEvent(userProfile.userId(), account.id(), account.nickname(),
                strategy.type(), strategy.ticker(), targetSeed)); // 사용자 알림 이벤트
    }

    // 마지막 CyclePosition의 deposit = MAX 시드의 내부 원장 기준
    private BigDecimal calcLastPositionDeposit(Strategy strategy, StrategyCycle currentCycle) {
        return cyclePositionPort.findLatestOneByStrategyId(strategy.id())
                .map(CyclePosition::deposit)
                .orElse(currentCycle.startAmount()); // fallback: 현재 사이클 시드
    }
}
