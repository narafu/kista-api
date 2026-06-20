package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.UserSettings;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.port.out.LoadUserSettingsPort;
import com.kista.domain.port.out.NotifyPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.UserNotificationPort;
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

    private final BrokerMarginRouter brokerMarginRouter;        // USD 매수가능금액 조회 (MAX 재등록)
    private final StrategyPort strategyPort;                   // 전략 상태 갱신
    private final StrategyCyclePort strategyCyclePort;         // 새 StrategyCycle 생성
    private final CyclePositionPort cyclePositionPort;         // 새 시작 스냅샷 저장
    private final NotifyPort notifyPort;                       // 관리자 알림 (잔고 부족·오류)
    private final UserNotificationPort userNotificationPort;   // 사용자 알림 (재등록 완료)
    private final CycleOrderStrategies cycleStrategies;        // 전략 타입별 최소금액 정책
    private final LoadUserSettingsPort loadUserSettingsPort;   // 잔고 검증 설정 조회 (user_settings)

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

        // 잔고검증 정책 — ON: 브로커 실잔고 조회, OFF: 내부 원장만 사용
        SeedResolutionPolicy policy = resolvePolicy(user, account, strategy);
        Optional<BigDecimal> balanceOpt = policy.resolveAvailableBalance(strategy, maintainSeed, maxSeed);
        if (balanceOpt.isEmpty()) return; // 브로커 조회 실패 — 내부에서 notifyError 완료
        BigDecimal actualBalance = balanceOpt.get();

        BigDecimal targetSeed;
        if (strategy.cycleSeedType() == Strategy.CycleSeedType.MAX) {
            if (actualBalance.compareTo(maxSeed) >= 0) {
                targetSeed = maxSeed;
            } else if (actualBalance.compareTo(maintainSeed) >= 0) {
                log.warn("[strategyId={}] MAX 잔고 부족 → MAINTAIN으로 강등: actual={}, max={}",
                        strategy.id(), actualBalance, maxSeed);
                targetSeed = maintainSeed;
            } else {
                // 실잔고가 maintainSeed에도 못 미침 → PAUSE
                log.warn("[strategyId={}] MAINTAIN도 부족 → PAUSED: actual={}, maintain={}",
                        strategy.id(), actualBalance, maintainSeed);
                strategyPort.save(strategy.withStatus(Strategy.Status.PAUSED));
                return;
            }
        } else { // MAINTAIN
            if (actualBalance.compareTo(maintainSeed) >= 0) {
                targetSeed = maintainSeed;
            } else {
                log.warn("[strategyId={}] MAINTAIN 잔고 부족 → PAUSED: actual={}, maintain={}",
                        strategy.id(), actualBalance, maintainSeed);
                strategyPort.save(strategy.withStatus(Strategy.Status.PAUSED));
                return;
            }
        }

        // 최소금액 가드 — 전략 타입별 정책은 전략 객체에 위임
        BigDecimal minRequired = cycleStrategies.of(strategy.type()).minRequiredDeposit(price, privacyTradeBase, strategy.divisionCount());
        if (minRequired != null && targetSeed.compareTo(minRequired) < 0) {
            log.warn("[strategyId={}] 재등록 취소 — 최소금액 미달: {} < {}", strategy.id(), targetSeed, minRequired);
            notifyPort.notifyInsufficientBalance(account,
                    new AccountBalance(0, null, targetSeed), strategy.ticker());
            return;
        }

        // 새 StrategyCycle + 시작 스냅샷 생성 (시드 결정 방식 stamp)
        StrategyCycle newCycle = strategyCyclePort.save(StrategyCycle.start(strategy.id(), targetSeed, policy.seedResolvedBy()));
        cyclePositionPort.save(CyclePosition.startSnapshot(newCycle.id(), targetSeed, price));
        log.info("[strategyId={}] 사이클 재등록 완료: {} → targetSeed={}", strategy.id(), strategy.cycleSeedType(), targetSeed);
        userNotificationPort.notifyNewCycleStarted(user, account, strategy, targetSeed); // 사용자 알림
    }

    // 잔고검증 설정에 따라 시드 결정 정책 선택
    private SeedResolutionPolicy resolvePolicy(User user, Account account, Strategy strategy) {
        UserSettings settings = loadUserSettingsPort.loadByUserId(user.id())
                .orElse(UserSettings.defaultFor(user.id())); // 미설정 시 기본값(검증 ON)
        if (!settings.balanceCheckEnabled()) {
            // OFF: 내부 원장만 사용 (브로커 조회 없음)
            return new SeedResolutionPolicy() {
                @Override
                public Optional<BigDecimal> resolveAvailableBalance(Strategy s, BigDecimal maintainSeed, BigDecimal maxSeed) {
                    return Optional.of(s.cycleSeedType() == Strategy.CycleSeedType.MAX ? maxSeed : maintainSeed);
                }
                @Override
                public StrategyCycle.SeedResolvedBy seedResolvedBy() { return StrategyCycle.SeedResolvedBy.LEDGER_ONLY; }
            };
        }
        // ON: 브로커 실잔고 조회
        return new SeedResolutionPolicy() {
            @Override
            public Optional<BigDecimal> resolveAvailableBalance(Strategy s, BigDecimal maintainSeed, BigDecimal maxSeed) {
                return Optional.ofNullable(fetchUsdBalance(s, account));
            }
            @Override
            public StrategyCycle.SeedResolvedBy seedResolvedBy() { return StrategyCycle.SeedResolvedBy.BROKER_VERIFIED; }
        };
    }

    // 마지막 CyclePosition의 usdDeposit = MAX 시드의 내부 원장 기준
    private BigDecimal calcLastPositionDeposit(Strategy strategy, StrategyCycle currentCycle) {
        return cyclePositionPort.findLatestByStrategyId(strategy.id(), 1).stream()
                .findFirst()
                .map(CyclePosition::usdDeposit)
                .orElse(currentCycle.startAmount()); // fallback: 현재 사이클 시드
    }

    // 브로커별 USD 매수가능금액 조회 — 실패 시 notifyError 후 null 반환
    private BigDecimal fetchUsdBalance(Strategy strategy, Account account) {
        try {
            BigDecimal usdAmount = brokerMarginRouter.getUsdBuyableAmount(account);
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
