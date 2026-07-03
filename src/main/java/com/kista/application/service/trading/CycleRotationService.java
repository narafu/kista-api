package com.kista.application.service.trading;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.strategy.StrategyInfiniteDetail;
import com.kista.domain.model.strategy.StrategyVersion;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.UserSettings;
import com.kista.domain.port.out.*;
import com.kista.domain.port.out.broker.MarginPort;
import com.kista.domain.strategy.CycleOrderStrategies;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

// 사이클 종료(holdings==0) 시 CycleSeedType 정책에 따라 새 StrategyCycle + 시작 스냅샷 생성
// NONE → 전략 PAUSED / MAINTAIN → 동일 startAmount 유지 / MAX → 내부 원장 기준 최대 시드
// package-private — application/service 패키지 전용
@Service
@RequiredArgsConstructor
@Slf4j
class CycleRotationService {

    private final BrokerAdapterRegistry registry;               // USD 매수가능금액 조회 (MAX 재등록)
    private final StrategyPort strategyPort;                   // 전략 상태 갱신
    private final StrategyVersionPort strategyVersionPort;     // 활성 전략 버전 조회/종료
    private final StrategyInfiniteDetailPort strategyInfiniteDetailPort;
    private final CyclePositionPort cyclePositionPort;         // MAX 시드 계산용 최신 포지션 조회 (읽기 전용)
    private final CycleSnapshotCreator cycleSnapshotCreator;   // StrategyCycle + CyclePosition 원자적 저장
    private final NotifyPort notifyPort;                       // 관리자 알림 (잔고 부족·오류)
    private final UserNotificationPort userNotificationPort;   // 사용자 알림 (재등록 완료)
    private final CycleOrderStrategies cycleStrategies;        // 전략 타입별 최소금액 정책
    private final UserSettingsPort userSettingsPort; // 잔고 검증 설정 조회 (user_settings)

    void rotate(Strategy strategy, StrategyCycle currentCycle, Account account, User user,
                BigDecimal price, PrivacyTradeBase privacyTradeBase) {

        if (strategy.cycleSeedType() == Strategy.CycleSeedType.NONE) {
            // NONE → 전략 PAUSED (연속 없음)
            strategyPort.save(strategy.withStatus(Strategy.Status.PAUSED));
            log.info("[strategyId={}] 사이클 종료 (NONE) → PAUSED", strategy.id());
            return;
        }

        BigDecimal maintainSeed = currentCycle.startAmount(); // MAINTAIN 기준 시드
        BigDecimal maxSeed = calcLastPositionDeposit(strategy, currentCycle); // MAX 기준 시드 (내부 원장)

        // 잔고검증 정책 — ON: 증권사 실잔고 조회, OFF: 내부 원장만 사용
        SeedResolutionPolicy policy = resolvePolicy(user, account, strategy);
        Optional<BigDecimal> balanceOpt = policy.resolveAvailableBalance(strategy, maintainSeed, maxSeed);
        if (balanceOpt.isEmpty()) return; // 증권사 조회 실패 — 내부에서 notifyError 완료
        BigDecimal actualBalance = balanceOpt.get();

        BigDecimal targetSeed = resolveTargetSeed(strategy, actualBalance, maintainSeed, maxSeed);
        if (targetSeed == null) return; // maintainSeed도 부족 — PAUSED 처리 완료

        // 최소금액 가드 — 전략 타입별 정책은 전략 객체에 위임
        int divisionCount = strategyInfiniteDetailPort.findActiveByStrategyId(strategy.id())
                .map(StrategyInfiniteDetail::divisionCount)
                .orElse(Strategy.DEFAULT_DIVISION_COUNT);
        BigDecimal minRequired = cycleStrategies.of(strategy.type()).minRequiredDeposit(price, privacyTradeBase, divisionCount);
        if (minRequired != null && targetSeed.compareTo(minRequired) < 0) {
            log.warn("[strategyId={}] 재등록 취소 — 최소금액 미달: {} < {}", strategy.id(), targetSeed, minRequired);
            notifyPort.notifyInsufficientBalance(account,
                    new AccountBalance(0, null, targetSeed), strategy.ticker());
            return;
        }

        // 새 StrategyCycle + 시작 스냅샷 원자적 생성 (시드 결정 방식 stamp)
        StrategyVersion activeVersion = strategyVersionPort.findActiveByStrategyId(strategy.id())
                .orElseThrow(() -> new IllegalStateException("활성 전략 버전이 없습니다: " + strategy.id()));
        StrategyCycle newCycle = cycleSnapshotCreator.createCycleAndSnapshot(
                strategy.id(), activeVersion.id(), targetSeed, price);
        log.info("[strategyId={}] 사이클 재등록 완료: {} → targetSeed={}", strategy.id(), strategy.cycleSeedType(), targetSeed);
        userNotificationPort.notifyNewCycleStarted(user, account, strategy, targetSeed); // 사용자 알림
    }

    // MAX/MAINTAIN 공통 목표 시드 결정 — maintainSeed 미달 시 PAUSED 처리 후 null 반환
    private BigDecimal resolveTargetSeed(Strategy strategy, BigDecimal actualBalance,
                                         BigDecimal maintainSeed, BigDecimal maxSeed) {
        if (strategy.cycleSeedType() == Strategy.CycleSeedType.MAX && actualBalance.compareTo(maxSeed) >= 0) {
            return maxSeed;
        }
        if (actualBalance.compareTo(maintainSeed) >= 0) {
            if (strategy.cycleSeedType() == Strategy.CycleSeedType.MAX) {
                log.warn("[strategyId={}] MAX 잔고 부족 → MAINTAIN으로 강등: actual={}, max={}",
                        strategy.id(), actualBalance, maxSeed);
            }
            return maintainSeed;
        }
        // 실잔고가 maintainSeed에도 못 미침 → PAUSE
        log.warn("[strategyId={}] MAINTAIN 잔고 부족 → PAUSED: actual={}, maintain={}",
                strategy.id(), actualBalance, maintainSeed);
        strategyPort.save(strategy.withStatus(Strategy.Status.PAUSED));
        return null;
    }

    // 잔고검증 설정에 따라 시드 결정 정책 선택
    private SeedResolutionPolicy resolvePolicy(User user, Account account, Strategy strategy) {
        UserSettings settings = userSettingsPort.findOrDefault(user.id()); // 미설정 시 기본값(검증 ON)
        if (!settings.balanceCheckEnabled()) {
            // OFF: 내부 원장만 사용 (증권사 조회 없음)
            return (s, maintainSeed, maxSeed) ->
                    Optional.of(s.cycleSeedType() == Strategy.CycleSeedType.MAX ? maxSeed : maintainSeed);
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
            BigDecimal usdAmount = registry.require(account, MarginPort.class).getUsdBuyableAmount(account);
            if (usdAmount == null || usdAmount.compareTo(BigDecimal.ZERO) == 0) {
                log.warn("[strategyId={}] 재등록 — USD 잔고 없음 (0 또는 null)", strategy.id());
                notifyPort.notifyError(new IllegalStateException("재등록 실패: USD 잔고 없음 strategyId=" + strategy.id()));
                return null;
            }
            return usdAmount;
        } catch (Exception e) {
            log.error("[strategyId={}] 재등록 — USD 잔고 조회 실패: {}", strategy.id(), e.getMessage());
            notifyPort.notifyError(e);
            return null;
        }
    }
}
