package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.Currency;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.*;
import com.kista.domain.strategy.CycleOrderStrategies;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

// 사이클 종료(holdings==0) 시 CycleSeedType 정책에 따라 새 StrategyCycle + 시작 스냅샷 생성
// NONE → 전략 PAUSED / MAINTAIN → 동일 initialUsdDeposit 유지 / MAX → 내부 원장 기준 최대 시드
// package-private — application/service 패키지 전용
@Service
@RequiredArgsConstructor
@Slf4j
class CycleRotationService {

    private final KisMarginPort kisMarginPort;                 // USD 잔고 조회 (MAX 재등록)
    private final StrategyPort strategyPort;                   // 전략 상태 갱신
    private final StrategyCyclePort strategyCyclePort;         // 새 StrategyCycle 생성
    private final CyclePositionPort cyclePositionPort;         // 새 시작 스냅샷 저장
    private final NotifyPort notifyPort;                       // 관리자 알림 (잔고 부족·오류)
    private final UserNotificationPort userNotificationPort;   // 사용자 알림 (재등록 완료)
    private final CycleOrderStrategies cycleStrategies;        // 전략 타입별 최소금액 정책

    void rotate(Strategy strategy, StrategyCycle currentCycle, Account account, User user,
                BigDecimal price, PrivacyTradeBase privacyTradeBase) {

        if (strategy.cycleSeedType() == Strategy.CycleSeedType.NONE) {
            // NONE → 전략 PAUSED (연속 없음)
            strategyPort.save(strategy.withStatus(Strategy.Status.PAUSED));
            log.info("[strategyId={}] 사이클 종료 (NONE) → PAUSED", strategy.id());
            return;
        }

        // MAINTAIN/MAX — KIS 실잔고 조회
        BigDecimal actualBalance = fetchKisUsdBalance(strategy, account);
        if (actualBalance == null) return; // 실패 — 내부에서 notifyError 완료

        BigDecimal maintainSeed = currentCycle.initialUsdDeposit(); // MAINTAIN 기준 시드
        BigDecimal maxSeed = calcLastPositionDeposit(strategy, currentCycle); // MAX 기준 시드 (내부 원장)

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
        BigDecimal minRequired = cycleStrategies.of(strategy.type()).minRequiredDeposit(price, privacyTradeBase);
        if (minRequired != null && targetSeed.compareTo(minRequired) < 0) {
            log.warn("[strategyId={}] 재등록 취소 — 최소금액 미달: {} < {}", strategy.id(), targetSeed, minRequired);
            notifyPort.notifyInsufficientBalance(account,
                    new AccountBalance(0, null, targetSeed), strategy.ticker());
            return;
        }

        // 새 StrategyCycle + 시작 스냅샷 생성
        StrategyCycle newCycle = strategyCyclePort.save(StrategyCycle.start(strategy.id(), targetSeed));
        cyclePositionPort.save(CyclePosition.startSnapshot(newCycle.id(), targetSeed, price));
        log.info("[strategyId={}] 사이클 재등록 완료: {} → targetSeed={}", strategy.id(), strategy.cycleSeedType(), targetSeed);
        userNotificationPort.notifyStrategyChanged(user, account, strategy, "재등록"); // 관리자 알림
        userNotificationPort.notifyNewCycleStarted(user, account, strategy, targetSeed); // 사용자 알림
    }

    // 마지막 CyclePosition의 usdDeposit = MAX 시드의 내부 원장 기준
    private BigDecimal calcLastPositionDeposit(Strategy strategy, StrategyCycle currentCycle) {
        return cyclePositionPort.findLatestByStrategyId(strategy.id(), 1).stream()
                .findFirst()
                .map(CyclePosition::usdDeposit)
                .orElse(currentCycle.initialUsdDeposit()); // fallback: 현재 사이클 시드
    }

    // KIS margin 조회 → USD 행의 통합주문가능금액
    private BigDecimal fetchKisUsdBalance(Strategy strategy, Account account) {
        List<MarginItem> margins;
        try {
            margins = kisMarginPort.getMargin(account);
        } catch (Exception e) {
            log.error("[strategyId={}] 재등록 — KIS 잔고 조회 실패: {}", strategy.id(), e.getMessage());
            notifyPort.notifyError(e);
            return null;
        }
        BigDecimal usdAmount = margins.stream()
                .filter(m -> Currency.USD == m.currency())
                .findFirst()
                .map(MarginItem::purchasableAmount)
                .orElse(null);
        if (usdAmount == null) {
            log.warn("[strategyId={}] 재등록 — USD 잔고 행 없음", strategy.id());
            notifyPort.notifyError(new IllegalStateException("재등록 실패: USD 잔고 없음 strategyId=" + strategy.id()));
        }
        return usdAmount;
    }
}
