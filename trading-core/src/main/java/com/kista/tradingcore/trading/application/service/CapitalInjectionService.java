package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.application.usecase.InjectCapitalUseCase;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.InjectCapitalCommand;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.NoSuchElementException;
import java.util.UUID;

// 진행 중 사이클 자금 추가 — 사이클은 유지하고 최신 포지션 행을 in-place 갱신(append하면 리버스 판정·별점가·이력이 왜곡), startAmount에 추가분을 가산한다 (브로커 호출·주문 취소 없음)
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class CapitalInjectionService implements InjectCapitalUseCase {

    private static final String NO_ACTIVE_CYCLE = "진행 중인 사이클이 없어 자금을 추가할 수 없습니다.";

    private final StrategyPort strategyPort;
    private final AccountPort accountPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;

    @Override
    public void inject(UUID strategyId, UUID requesterId, InjectCapitalCommand cmd) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        accountPort.requireOwnedAccount(strategy.accountId(), requesterId);

        BigDecimal deposit = cmd.depositAmount() != null ? cmd.depositAmount() : BigDecimal.ZERO;
        int shares = cmd.shares() != null ? cmd.shares() : 0;
        if (cmd.depositAmount() == null && cmd.shares() == null) {
            throw new IllegalArgumentException("추가할 예수금 또는 주식을 입력해야 합니다.");
        }
        if ((cmd.shares() == null) != (cmd.sharePrice() == null)) {
            throw new IllegalArgumentException("주식을 추가하려면 수량과 매수단가를 함께 입력해야 합니다.");
        }
        if ((cmd.depositAmount() != null && deposit.signum() <= 0) || (cmd.shares() != null && shares <= 0)
                || (cmd.sharePrice() != null && cmd.sharePrice().signum() <= 0)) {
            throw new IllegalArgumentException("추가 금액과 수량은 0보다 커야 합니다.");
        }
        if (!strategy.isPrivacy() && !strategy.isInfinite()) {
            throw new IllegalArgumentException("자금 추가는 PRIVACY·INFINITE 전략만 지원합니다.");
        }
        // ponytail: StrategyStatus가 ACTIVE/PAUSED 두 값뿐이라 상태 검사는 생략 — 값이 늘면 여기서 거른다
        StrategyCycle locked = strategyCyclePort.requireLatestByStrategyId(strategyId);
        if (locked.endDate() != null) {
            throw new IllegalStateException(NO_ACTIVE_CYCLE);
        }

        // 사이클 행 잠금 — 수동 체결 보정과 직렬화
        strategyCyclePort.lockForUpdate(locked.id());
        // 잠금 후 재조회 — 잠금 대기 중 종료·startAmount 변경분 반영 (lost update 방지)
        // ponytail: 마감 리포트의 markEnded는 사이클 락을 잡지 않아 재조회~커밋 사이 ms 창이 남음
        StrategyCycle cycle = strategyCyclePort.requireLatestByStrategyId(strategyId);
        if (cycle.endDate() != null || !cycle.id().equals(locked.id())) {
            throw new IllegalStateException(NO_ACTIVE_CYCLE);
        }
        CyclePosition latest = cyclePositionPort.findLatestOne(cycle.id())
                .orElseThrow(() -> new NoSuchElementException("최신 포지션 이력이 없습니다: cycleId=" + cycle.id()));

        AccountBalance old = latest.toBalance();
        BigDecimal sharesBd = BigDecimal.valueOf(shares);
        BigDecimal newHoldings = old.holdings().add(sharesBd);
        BigDecimal newAvg = old.avgPrice();
        BigDecimal injectedCost = BigDecimal.ZERO;
        if (shares > 0) {
            injectedCost = cmd.sharePrice().multiply(sharesBd);
            BigDecimal existingCost = old.avgPrice() != null ? old.avgPrice().multiply(old.holdings()) : BigDecimal.ZERO;
            newAvg = existingCost.add(injectedCost).divide(newHoldings, 4, RoundingMode.HALF_UP);
        }
        AccountBalance newBalance = new AccountBalance(newHoldings, newAvg, old.usdDeposit().add(deposit));

        // 최신 행 in-place 갱신 — detail(리버스모드)은 같은 행에 붙어 있어 그대로 유지
        cyclePositionPort.updateBalance(latest.id(), newBalance);
        strategyCyclePort.updateStartAmount(cycle.id(), cycle.startAmount().add(deposit).add(injectedCost));

        log.info("[strategyId={}] 자금 추가: deposit={}, shares={}, sharePrice={}, holdings={}, usdDeposit={}",
                strategyId, deposit, shares, cmd.sharePrice(), newBalance.holdings(), newBalance.usdDeposit());
    }
}
