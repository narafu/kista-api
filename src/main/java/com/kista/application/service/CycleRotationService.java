package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.Currency;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.tradingcycle.TradingCycleHistory;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

import static java.math.RoundingMode.HALF_UP;

// 사이클 종료 시 cycleSeedType(MAINTAIN/MAX)에 따라 initialUsdDeposit 갱신 + 새 이력 생성
// package-private — application/service 패키지 전용
@Service
@RequiredArgsConstructor
@Slf4j
class CycleRotationService {

    private final KisMarginPort kisMarginPort;                 // MAX 재등록 시 USD 잔고 조회
    private final TradingCyclePort cyclePort;                  // 사이클 갱신
    private final TradingCycleHistoryPort cycleHistoryPort;    // 새 시작점 이력
    private final NotifyPort notifyPort;                       // 관리자 알림 (잔고 부족·오류)
    private final UserNotificationPort userNotificationPort;   // 사용자 알림 (재등록 완료)

    void rotate(TradingCycle cycle, Account account, User user,
                BigDecimal price, PrivacyTradeBase privacyTradeBase) {
        // 1. nextInitialUsdDeposit 계산
        BigDecimal nextDeposit = calcNextDeposit(cycle, account);
        if (nextDeposit == null) return; // 실패 — 내부에서 알림 발송 완료

        // 2. 최소금액 가드
        BigDecimal minRequired = resolveMinRequired(cycle, price, privacyTradeBase);
        if (minRequired != null && nextDeposit.compareTo(minRequired) < 0) {
            log.warn("[cycleId={}] 재등록 취소 — 잔고 부족: {} < 최소 {}", cycle.id(), nextDeposit, minRequired);
            notifyPort.notifyInsufficientBalance(account,
                    new AccountBalance(0, null, nextDeposit), cycle.ticker());
            return;
        }

        // 3. cycle 갱신 (initialUsdDeposit만 변경, 동일 ID 유지)
        TradingCycle rotated = new TradingCycle(
                cycle.id(), cycle.accountId(), cycle.type(), TradingCycle.Status.ACTIVE,
                cycle.ticker(), nextDeposit, cycle.cycleSeedType()
        );
        cyclePort.save(rotated);

        // 4. 새 시작점 이력 (holdings=0, avgPrice=null)
        cycleHistoryPort.save(new TradingCycleHistory(
                null, cycle.id(), nextDeposit, price, null, 0, null
        ));
        log.info("[cycleId={}] 사이클 재등록 완료: {} → initialUsdDeposit={}", cycle.id(), cycle.cycleSeedType(), nextDeposit);
        userNotificationPort.notifyStrategyChanged(user, account, rotated, "재등록");
    }

    private BigDecimal calcNextDeposit(TradingCycle cycle, Account account) {
        if (cycle.cycleSeedType() == TradingCycle.CycleSeedType.MAINTAIN) {
            return cycle.initialUsdDeposit();
        }
        // MAX — KIS 잔고에서 USD 통합주문가능금액 조회
        List<MarginItem> margins;
        try {
            margins = kisMarginPort.getMargin(account);
        } catch (Exception e) {
            log.error("[cycleId={}] MAX 재등록 — KIS 잔고 조회 실패: {}", cycle.id(), e.getMessage());
            notifyPort.notifyError(e);
            return null;
        }
        BigDecimal usdAmount = margins.stream()
                .filter(m -> Currency.USD == m.currency())
                .findFirst()
                .map(MarginItem::purchasableAmount)
                .orElse(null);
        if (usdAmount == null) {
            log.warn("[cycleId={}] MAX 재등록 — USD 잔고 행 없음", cycle.id());
            notifyPort.notifyError(new IllegalStateException("MAX 재등록 실패: USD 잔고 없음 cycleId=" + cycle.id()));
        }
        return usdAmount;
    }

    // 최소금액 기준: INFINITE = 현재가 × 44 (= 20round × 2 × 1.1 safety), PRIVACY = currentCycleStart / 2
    private BigDecimal resolveMinRequired(TradingCycle cycle, BigDecimal price, PrivacyTradeBase privacyTradeBase) {
        return switch (cycle.type()) {
            case INFINITE -> price != null
                    ? price.multiply(BigDecimal.valueOf(44)).setScale(2, HALF_UP)
                    : null;
            case PRIVACY -> privacyTradeBase != null
                    ? privacyTradeBase.currentCycleStart().divide(BigDecimal.valueOf(2), 2, HALF_UP)
                    : null;
        };
    }
}
