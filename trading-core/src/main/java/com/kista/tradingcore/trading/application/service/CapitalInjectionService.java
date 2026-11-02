package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.TradingBatchRunPort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.application.usecase.InjectCapitalUseCase;
import com.kista.tradingcore.trading.application.usecase.WithdrawCapitalUseCase;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.DstInfo;
import com.kista.tradingcore.trading.domain.model.InjectCapitalCommand;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.domain.model.WithdrawCapitalCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Stream;

// 진행 중 사이클 투자금 추가·인출 — 같은 사이클 잠금 로직을 공유한다. 인출은 장부만 차감하고 반영은 다음 주문 계산부터다(INFINITE unitAmount·PRIVACY multiple 감소, 넘친 보유분은 adjustBuyQuantities가 매수를 줄여 흡수하며 강제 매도는 없음).
// 추가 — 사이클은 유지하고 최신 포지션 행을 in-place 갱신(append하면 리버스 판정·별점가·이력이 왜곡), startAmount에 추가분을 가산한다 (브로커 호출·주문 취소 없음)
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class CapitalInjectionService implements InjectCapitalUseCase, WithdrawCapitalUseCase {

    private static final String NO_ACTIVE_CYCLE = "진행 중인 사이클이 없어 투자금을 추가할 수 없습니다.";
    private static final String NO_ACTIVE_CYCLE_WITHDRAW = "진행 중인 사이클이 없어 투자금을 인출할 수 없습니다.";
    private static final String REPORT_WINDOW = "오늘 마감 리포트가 끝나기 전에는 투자금을 추가할 수 없습니다.";
    private static final String REPORT_WINDOW_WITHDRAW = "오늘 마감 리포트가 끝나기 전에는 투자금을 인출할 수 없습니다.";

    // 잠금 후 재조회한 진행 중 사이클과 그 최신 포지션
    private record LockedCycle(StrategyCycle cycle, CyclePosition latest) {}

    private final StrategyPort strategyPort;
    private final AccountPort accountPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final OrderPort orderPort;
    private final TradingBatchRunPort batchRunPort;
    private final StrategyLedgerBudget ledgerBudget;
    private final Clock clock;

    @Override
    public void inject(UUID strategyId, UUID requesterId, InjectCapitalCommand cmd) {
        Strategy strategy = ownedStrategy(strategyId, requesterId);

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
        requireSupportedType(strategy, "투자금 추가는 PRIVACY·INFINITE 전략만 지원합니다.");
        LockedCycle locked = lockActiveCycle(strategy, NO_ACTIVE_CYCLE, REPORT_WINDOW);
        StrategyCycle cycle = locked.cycle();
        CyclePosition latest = locked.latest();

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

        log.info("[strategyId={}] 투자금 추가: deposit={}, shares={}, sharePrice={}, holdings={}, usdDeposit={}",
                strategyId, deposit, shares, cmd.sharePrice(), newBalance.holdings(), newBalance.usdDeposit());
    }

    @Override
    public void withdraw(UUID strategyId, UUID requesterId, WithdrawCapitalCommand cmd) {
        Strategy strategy = ownedStrategy(strategyId, requesterId);
        BigDecimal amount = cmd.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("투자금 인출 금액은 0보다 커야 합니다.");
        }
        requireSupportedType(strategy, "투자금 인출은 PRIVACY·INFINITE 전략만 지원합니다.");
        LockedCycle locked = lockActiveCycle(strategy, NO_ACTIVE_CYCLE_WITHDRAW, REPORT_WINDOW_WITHDRAW);
        StrategyCycle cycle = locked.cycle();
        CyclePosition latest = locked.latest();

        // 인출 가능액 = 장부 예수금 − 체결 전 BUY 약정. 오늘 마감 전 주문과 22:00 개장 배치가 미리 저장한 다음 거래일 주문이
        // 장부 예수금으로 체결되므로 두 날짜를 모두 빼야 체결 후 usdDeposit이 음수가 되지 않는다(StrategyLedgerBudget.remaining과 같은 약정 정의)
        LocalDate today = LocalDate.now(clock);
        LocalDate nextTradeDate = DstInfo.nextTradeDate(ZonedDateTime.now(clock));
        List<Order> committedOrders = Stream.of(today, nextTradeDate).distinct()
                .flatMap(date -> orderPort.findPlannedOrPlacedByCycleAndDate(cycle.id(), date).stream())
                .toList();
        BigDecimal available = ledgerBudget.remaining(cycle, Optional.of(latest), committedOrders);
        if (amount.compareTo(available) > 0) {
            // 금액 표기는 kista-ui fmtUsd와 같은 #,##0.00 (JVM 로케일 무관), 음수 가용액은 0으로 표시, 표시액을 그대로 요청해도 통과하도록 내림
            throw new IllegalArgumentException(("투자금 인출 금액이 인출 가능액 $%s를 초과합니다. "
                    + "장부 예수금에서 체결 전 매수 주문 금액을 뺀 만큼 인출할 수 있습니다.")
                    .formatted(String.format(Locale.US, "%,.2f", available.max(BigDecimal.ZERO).setScale(2, RoundingMode.DOWN))));
        }
        BigDecimal newStartAmount = cycle.startAmount().subtract(amount);
        if (newStartAmount.signum() <= 0) {
            // PRIVACY 배수 = startAmount ÷ currentCycleStart가 깨진다
            throw new IllegalArgumentException("투자금 인출 후 시작 금액이 0 이하가 되어 인출할 수 없습니다.");
        }

        AccountBalance old = latest.toBalance();
        AccountBalance newBalance = new AccountBalance(old.holdings(), old.avgPrice(), old.usdDeposit().subtract(amount));
        // 최신 행 in-place 갱신 — append하면 리버스 판정·별점가·이력이 왜곡되고 detail은 같은 행에 유지된다
        cyclePositionPort.updateBalance(latest.id(), newBalance);
        strategyCyclePort.updateStartAmount(cycle.id(), newStartAmount);

        log.info("[strategyId={}] 투자금 인출: amount={}, usdDeposit={}, startAmount={}",
                strategyId, amount, newBalance.usdDeposit(), newStartAmount);
    }

    // 전략 조회 + 소유권 검증
    private Strategy ownedStrategy(UUID strategyId, UUID requesterId) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        return strategy;
    }

    private void requireSupportedType(Strategy strategy, String message) {
        if (!strategy.isPrivacy() && !strategy.isInfinite()) {
            throw new IllegalArgumentException(message);
        }
    }

    // 최신 사이클 종료 검사 → 행 잠금 → 재조회(잠금 대기 중 종료·startAmount 변경분 반영) → 최신 포지션 조회
    private LockedCycle lockActiveCycle(Strategy strategy, String noActiveMessage, String reportWindowMessage) {
        UUID strategyId = strategy.id();
        // ponytail: StrategyStatus가 ACTIVE/PAUSED 두 값뿐이라 상태 검사는 생략 — 값이 늘면 여기서 거른다
        StrategyCycle first = strategyCyclePort.requireLatestByStrategyId(strategyId);
        if (first.endDate() != null) {
            throw new IllegalStateException(noActiveMessage);
        }

        // 사이클 행 잠금 — 수동 체결 보정과 직렬화
        strategyCyclePort.lockForUpdate(first.id());
        // ponytail: 마감 리포트의 markEnded는 사이클 락을 잡지 않아 재조회~커밋 사이 ms 창이 남음
        StrategyCycle cycle = strategyCyclePort.requireLatestByStrategyId(strategyId);
        if (cycle.endDate() != null || !cycle.id().equals(first.id())) {
            throw new IllegalStateException(noActiveMessage);
        }
        requireOutsideReportWindow(strategy, reportWindowMessage);
        CyclePosition latest = cyclePositionPort.findLatestOne(cycle.id())
                .orElseThrow(() -> new NoSuchElementException("최신 포지션 이력이 없습니다: cycleId=" + cycle.id()));
        return new LockedCycle(cycle, latest);
    }

    // 마감 리포트 창(오늘 postClose 이후 ~ 리포트 마커 전) 거절 — 리포트는 postClose 이후 읽은 최신 포지션 잔고에 브로커 호출(수 초) 뒤 체결을 반영해 새 행을 append하므로,
    // 그 사이 커밋된 in-place 변경(예수금)은 덮어써지고 startAmount 변경만 남는다. postClose 이전 변경은 리포트가 재조회하므로 안전하다.
    // 리포트가 실패해 마커가 안 찍히면 그날은 계속 거절된다(다음 날 해제). 주문 없는 전략은 리포트 대상이 아니므로 제외(TradingService.resumeCloseReport와 같은 기준)
    private void requireOutsideReportWindow(Strategy strategy, String message) {
        if (DstInfo.calculate(ZonedDateTime.now(clock)).waitUntilPostClose(clock.instant()).isPositive()) {
            return;
        }
        LocalDate today = LocalDate.now(clock);
        if (batchRunPort.isReported(today, strategy.id())) {
            return;
        }
        boolean hasStrategyOrder = orderPort.findByStrategyId(strategy.id(), today, today).stream()
                .anyMatch(o -> !Order.UNKNOWN_LEG.equals(o.orderLeg()));
        if (hasStrategyOrder) {
            throw new IllegalStateException(message);
        }
    }
}
