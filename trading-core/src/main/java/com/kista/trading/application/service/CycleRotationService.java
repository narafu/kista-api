package com.kista.trading.application.service;
import com.kista.trading.application.service.support.SeedResolutionPolicy;

import com.kista.sharedkernel.NewCycleStartedEvent;
import com.kista.sharedkernel.TradingErrorEvent;
import com.kista.sharedkernel.InsufficientBalanceEvent;
import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.account.domain.model.Account;
import com.kista.privacy.domain.model.PrivacyTradeBase;
import com.kista.trading.domain.model.CyclePosition;
import com.kista.trading.domain.model.Strategy;
import com.kista.trading.domain.model.StrategyCycle;
import com.kista.trading.domain.model.StrategyInfiniteDetail;
import com.kista.trading.domain.model.StrategyVersion;
import com.kista.trading.domain.model.TradingUserProfile;
import com.kista.trading.application.port.output.*;
import com.kista.broker.application.port.output.MarginPort;
import com.kista.matching.domain.strategy.CycleOrderStrategies;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;
import com.kista.sharedkernel.StrategyCycleSeedType;
import com.kista.sharedkernel.StrategyDefaults;

// 사이클 종료(holdings==0) 시 CycleSeedType 정책에 따라 새 StrategyCycle + 시작 스냅샷 생성
// NONE → 전략 PAUSED / MAINTAIN → 동일 startAmount 유지 / MAX → 내부 원장 기준 최대 시드
// package-private — application/service 패키지 전용
@Service
@RequiredArgsConstructor
@Slf4j
class CycleRotationService {

    private final BrokerAdapterRegistry registry;               // USD 매수가능금액 조회 (MAX 재등록)
    private final StrategyPort strategyPort;                   // 시스템 자동 일시정지(사이클 재등록 실패)에도 사용
    private final StrategyVersionPort strategyVersionPort;     // 활성 전략 버전 조회/종료
    private final StrategyInfiniteDetailPort strategyInfiniteDetailPort;
    private final CyclePositionPort cyclePositionPort;         // MAX 시드 계산용 최신 포지션 조회 (읽기 전용)
    private final CycleSnapshotCreator cycleSnapshotCreator;   // StrategyCycle + CyclePosition 원자적 저장
    private final ApplicationEventPublisher eventPublisher;    // 새 사이클 시작 이벤트 발행 (재등록 완료) + 관리자 알림 이벤트 (잔고 부족·오류)
    private final CycleOrderStrategies cycleStrategies;        // 전략 타입별 최소금액 정책

    void rotate(Strategy strategy, StrategyCycle currentCycle, Account account, TradingUserProfile userProfile,
                BigDecimal price, PrivacyTradeBase privacyTradeBase) {

        if (strategy.cycleSeedType() == StrategyCycleSeedType.NONE) {
            // NONE → 전략 PAUSED (연속 없음)
            strategyPort.pause(strategy.id());
            log.info("[strategyId={}] 사이클 종료 (NONE) → PAUSED", strategy.id());
            return;
        }

        BigDecimal maintainSeed = currentCycle.startAmount(); // MAINTAIN 기준 시드
        BigDecimal maxSeed = calcLastPositionDeposit(strategy, currentCycle); // MAX 기준 시드 (내부 원장)

        // 잔고검증 정책 — ON: 증권사 실잔고 조회, OFF: 내부 원장만 사용
        SeedResolutionPolicy policy = resolvePolicy(userProfile, account, strategy);
        Optional<BigDecimal> balanceOpt = policy.resolveAvailableBalance(strategy, maintainSeed, maxSeed);
        if (balanceOpt.isEmpty()) return; // 증권사 조회 실패 — 내부에서 notifyError 완료
        BigDecimal actualBalance = balanceOpt.get();

        BigDecimal targetSeed = resolveTargetSeed(strategy, actualBalance, maintainSeed, maxSeed);
        if (targetSeed == null) return; // maintainSeed도 부족 — PAUSED 처리 완료

        // 최소금액 가드 — 전략 타입별 정책은 전략 객체에 위임. 미달이어도 재등록 자체는 차단하지 않고
        // 정보성 알림만 발행한다(예전엔 여기서 재등록을 취소했으나, 그러면 새 사이클 없이 종료 사이클만 남는
        // "좀비 사이클" 상태가 되어 BatchContextFactory가 매 배치마다 오류 알림을 반복 발행했다) — cycleSeedType대로
        // 그대로 등록해 축소된 시드로라도 매매가 이어지게 하고, 부족 여부는 알림으로만 알린다
        int divisionCount = strategyInfiniteDetailPort.findActiveByStrategyId(strategy.id())
                .map(StrategyInfiniteDetail::divisionCount)
                .orElse(StrategyDefaults.DEFAULT_DIVISION_COUNT);
        BigDecimal minRequired = cycleStrategies.of(strategy.type()).minRequiredDeposit(price, privacyTradeBase, divisionCount);
        if (minRequired != null && targetSeed.compareTo(minRequired) < 0) {
            log.warn("[strategyId={}] 최소금액 미달 — 축소된 시드로 재등록 진행: {} < {}", strategy.id(), targetSeed, minRequired);
            eventPublisher.publishEvent(new InsufficientBalanceEvent(null, account.id(), account.nickname(),
                    0, targetSeed, strategy.ticker(), null));
        }

        // 새 StrategyCycle + 시작 스냅샷 원자적 생성 (시드 결정 방식 stamp)
        StrategyVersion activeVersion = strategyVersionPort.findActiveByStrategyId(strategy.id())
                .orElseThrow(() -> new IllegalStateException("활성 전략 버전이 없습니다: " + strategy.id()));
        StrategyCycle newCycle = cycleSnapshotCreator.createCycleAndSnapshot(
                strategy.id(), activeVersion.id(), targetSeed, price);
        log.info("[strategyId={}] 사이클 재등록 완료: {} → targetSeed={}", strategy.id(), strategy.cycleSeedType(), targetSeed);
        eventPublisher.publishEvent(new NewCycleStartedEvent(userProfile.userId(), account.id(), account.nickname(),
                strategy.type(), strategy.ticker(), targetSeed)); // 사용자 알림 이벤트
    }

    // MAX/MAINTAIN 공통 목표 시드 결정 — maintainSeed 미달 시 PAUSED 처리 후 null 반환
    private BigDecimal resolveTargetSeed(Strategy strategy, BigDecimal actualBalance,
                                         BigDecimal maintainSeed, BigDecimal maxSeed) {
        if (strategy.cycleSeedType() == StrategyCycleSeedType.MAX && actualBalance.compareTo(maxSeed) >= 0) {
            return maxSeed;
        }
        if (actualBalance.compareTo(maintainSeed) >= 0) {
            if (strategy.cycleSeedType() == StrategyCycleSeedType.MAX) {
                log.warn("[strategyId={}] MAX 잔고 부족 → MAINTAIN으로 강등: actual={}, max={}",
                        strategy.id(), actualBalance, maxSeed);
            }
            return maintainSeed;
        }
        // 실잔고가 maintainSeed에도 못 미침 → PAUSE
        log.warn("[strategyId={}] MAINTAIN 잔고 부족 → PAUSED: actual={}, maintain={}",
                strategy.id(), actualBalance, maintainSeed);
        strategyPort.pause(strategy.id());
        return null;
    }

    // 잔고검증 설정에 따라 시드 결정 정책 선택
    private SeedResolutionPolicy resolvePolicy(TradingUserProfile userProfile, Account account, Strategy strategy) {
        if (!userProfile.balanceCheckEnabled()) {
            // OFF: 내부 원장만 사용 (증권사 조회 없음)
            return (s, maintainSeed, maxSeed) ->
                    Optional.of(s.cycleSeedType() == StrategyCycleSeedType.MAX ? maxSeed : maintainSeed);
        }
        // ON: 증권사 실잔고 조회
        return (s, maintainSeed, maxSeed) -> Optional.ofNullable(fetchUsdBalance(s, account));
    }

    // 마지막 CyclePosition의 usdDeposit = MAX 시드의 내부 원장 기준
    private BigDecimal calcLastPositionDeposit(Strategy strategy, StrategyCycle currentCycle) {
        return cyclePositionPort.findLatestOneByStrategyId(strategy.id())
                .map(CyclePosition::usdDeposit)
                .orElse(currentCycle.startAmount()); // fallback: 현재 사이클 시드
    }

    // 브로커별 USD 매수가능금액 조회 — 실패 시 notifyError 후 null 반환
    private BigDecimal fetchUsdBalance(Strategy strategy, Account account) {
        try {
            BigDecimal usdAmount = registry.require(account.toBrokerRef(), MarginPort.class).getUsdBuyableAmount(account.toBrokerRef());
            if (usdAmount == null || usdAmount.compareTo(BigDecimal.ZERO) == 0) {
                log.warn("[strategyId={}] 재등록 — USD 잔고 없음 (0 또는 null)", strategy.id());
                eventPublisher.publishEvent(new TradingErrorEvent(null,
                        "재등록 실패: USD 잔고 없음 strategyId=" + strategy.id()));
                return null;
            }
            return usdAmount;
        } catch (Exception e) {
            log.error("[strategyId={}] 재등록 — USD 잔고 조회 실패: {}", strategy.id(), e.getMessage());
            eventPublisher.publishEvent(new TradingErrorEvent(null, e.getMessage()));
            return null;
        }
    }
}
