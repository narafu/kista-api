package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.*;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.*;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// VR N주 사이클 롤오버 단위 테스트
// due 판정·V′ 계산·markEnded·새 사이클 생성·각종 skip 조건 검증
@ExtendWith(MockitoExtension.class)
@DisplayName("VrCycleRolloverService 단위 테스트")
class VrCycleRolloverServiceTest {

    @Mock StrategyCycleVrPort strategyCycleVrPort;
    @Mock StrategyVrDetailPort strategyVrDetailPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CycleSnapshotCreator cycleSnapshotCreator;
    @Mock NotifyPort notifyPort;
    @Mock UserNotificationPort userNotificationPort;

    VrCycleRolloverService service;

    // 고정 UUID 상수
    static final UUID STRATEGY_ID = UUID.randomUUID();
    static final UUID CYCLE_ID = UUID.randomUUID();
    static final UUID STRATEGY_VERSION_ID = UUID.randomUUID();
    static final UUID ACCOUNT_ID = UUID.randomUUID();
    static final UUID USER_ID = UUID.randomUUID();

    static final BigDecimal CLOSING_PRICE = new BigDecimal("50.00");
    static final BigDecimal USD_DEPOSIT = new BigDecimal("1000.00");
    // 10주 보유, 평단가=45.00
    static final AccountBalance POST_BALANCE = new AccountBalance(10, new BigDecimal("45.00"), USD_DEPOSIT);

    static final Account ACCOUNT = DomainFixtures.kisAccount(ACCOUNT_ID, USER_ID);

    static final User USER = DomainFixtures.activeUserWithTelegram(USER_ID);

    // 테스트 기준일: 2026-06-01 (기준, 실제 today는 각 테스트에서 지정)
    static final LocalDate CYCLE_START = LocalDate.of(2026, 6, 1);

    // 현재 사이클: startDate = CYCLE_START
    static final StrategyCycle CYCLE = new StrategyCycle(
            CYCLE_ID, STRATEGY_ID, STRATEGY_VERSION_ID,
            USD_DEPOSIT, null,
            CYCLE_START, null,
            Instant.now(), null);

    static final Strategy VR_STRATEGY = new Strategy(
            STRATEGY_ID, ACCOUNT_ID, Strategy.Type.VR,
            Strategy.Status.ACTIVE, Ticker.TQQQ, Strategy.CycleSeedType.MAINTAIN);

    // 4주 주기, bandWidth=15, recurringAmount=0 → gradient=10, poolLimitRate=0.50
    static final StrategyVrDetail DETAIL = new StrategyVrDetail(STRATEGY_VERSION_ID, 4, new BigDecimal("15.00"), 0);

    // 사이클 VR 상세: value=55.00, gradient=10, poolLimit=500
    static final StrategyCycleVrDetail CYCLE_VR = new StrategyCycleVrDetail(
            CYCLE_ID, new BigDecimal("55.00"), 10, new BigDecimal("500.00"));

    BatchContext ctx;

    @BeforeEach
    void setUp() {
        service = new VrCycleRolloverService(
                strategyCycleVrPort, strategyVrDetailPort, strategyCyclePort,
                cycleSnapshotCreator, notifyPort, userNotificationPort);
        ctx = new BatchContext(VR_STRATEGY, CYCLE, ACCOUNT, USER);
    }

    @Test
    @DisplayName("due 미도래 — 조용히 return, markEnded 미호출")
    void notDue_returnsQuietly() {
        // dueDate = 2026-06-01 + 4주 = 2026-06-29. today = 2026-06-28 → 미도래
        LocalDate today = CYCLE_START.plusWeeks(4).minusDays(1);
        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(CYCLE_VR));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(DETAIL));

        service.rollIfDue(ctx, POST_BALANCE, CLOSING_PRICE, today);

        verify(strategyCyclePort, never()).markEnded(any(), any(), any());
        verify(cycleSnapshotCreator, never()).createVrCycleAndSnapshot(any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("due 당일 도래 — markEnded + 새 사이클 생성")
    void due_onDueDate_rollsOver() {
        // dueDate = CYCLE_START + 4주 = 당일
        LocalDate today = CYCLE_START.plusWeeks(4);
        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(CYCLE_VR));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(DETAIL));
        when(cycleSnapshotCreator.createVrCycleAndSnapshot(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, STRATEGY_VERSION_ID,
                        USD_DEPOSIT, null, today, null, Instant.now(), null));

        service.rollIfDue(ctx, POST_BALANCE, CLOSING_PRICE, today);

        verify(strategyCyclePort).markEnded(CYCLE_ID, USD_DEPOSIT, today);
        verify(cycleSnapshotCreator).createVrCycleAndSnapshot(
                eq(STRATEGY_ID), eq(STRATEGY_VERSION_ID),
                eq(POST_BALANCE), eq(CLOSING_PRICE),
                any(BigDecimal.class), eq(10), any(BigDecimal.class));
    }

    @Test
    @DisplayName("due 지난 후 도래 — markEnded + 새 사이클 생성 (지연 이월)")
    void due_afterDueDate_rollsOver() {
        // 휴장일로 밀린 케이스: today = dueDate + 1일
        LocalDate today = CYCLE_START.plusWeeks(4).plusDays(1);
        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(CYCLE_VR));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(DETAIL));
        when(cycleSnapshotCreator.createVrCycleAndSnapshot(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, STRATEGY_VERSION_ID,
                        USD_DEPOSIT, null, today, null, Instant.now(), null));

        service.rollIfDue(ctx, POST_BALANCE, CLOSING_PRICE, today);

        verify(strategyCyclePort).markEnded(CYCLE_ID, USD_DEPOSIT, today);
        verify(cycleSnapshotCreator).createVrCycleAndSnapshot(any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("V′ 계산값 검증 — gradient=10, recurringAmount=0, evaluation=holdings×closingPrice")
    void newValue_calculatedCorrectly() {
        // V′ = V + pool/G + recurring + (evaluation − V)/(2√G)
        // = 55 + 1000/10 + 0 + (10×50 − 55)/(2×√10)
        // = 55 + 100 + 0 + (500 − 55)/(2×3.16227...)
        // = 55 + 100 + 445/6.32455... ≈ 55 + 100 + 70.39... ≈ 225.39...
        LocalDate today = CYCLE_START.plusWeeks(4);
        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(CYCLE_VR));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(DETAIL));
        when(cycleSnapshotCreator.createVrCycleAndSnapshot(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, STRATEGY_VERSION_ID,
                        USD_DEPOSIT, null, today, null, Instant.now(), null));

        service.rollIfDue(ctx, POST_BALANCE, CLOSING_PRICE, today);

        // newValue 캡처 후 범위 검증 (소수점 반올림 허용 ±0.01)
        ArgumentCaptor<BigDecimal> newValueCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(cycleSnapshotCreator).createVrCycleAndSnapshot(
                any(), any(), any(), any(), newValueCaptor.capture(), anyInt(), any());
        BigDecimal expectedNewValue = VrPosition.nextValue(
                CYCLE_VR.value(), USD_DEPOSIT, CYCLE_VR.gradient(), DETAIL.recurringAmount(),
                BigDecimal.valueOf(POST_BALANCE.holdings()).multiply(CLOSING_PRICE));
        assertThat(newValueCaptor.getValue()).isEqualByComparingTo(expectedNewValue);
    }

    @Test
    @DisplayName("gradient 이월 — cycleVr.gradient() 값이 새 사이클에 그대로 전달")
    void gradient_passedThrough() {
        LocalDate today = CYCLE_START.plusWeeks(4);
        // gradient=10 (cycleVr 고정값)
        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(CYCLE_VR));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(DETAIL));
        when(cycleSnapshotCreator.createVrCycleAndSnapshot(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, STRATEGY_VERSION_ID,
                        USD_DEPOSIT, null, today, null, Instant.now(), null));

        service.rollIfDue(ctx, POST_BALANCE, CLOSING_PRICE, today);

        ArgumentCaptor<Integer> gradientCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(cycleSnapshotCreator).createVrCycleAndSnapshot(
                any(), any(), any(), any(), any(), gradientCaptor.capture(), any());
        assertThat(gradientCaptor.getValue()).isEqualTo(CYCLE_VR.gradient()); // 10 이월
    }

    @Test
    @DisplayName("poolLimit 재계산 scale=2 — 새 pool × poolLimitRate (HALF_UP)")
    void poolLimit_recalculatedWithScale2() {
        // DETAIL.recurringAmount=0 → poolLimitRate=0.50
        // poolLimit = 1000.00 × 0.50 = 500.00 (scale=2)
        LocalDate today = CYCLE_START.plusWeeks(4);
        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(CYCLE_VR));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(DETAIL));
        when(cycleSnapshotCreator.createVrCycleAndSnapshot(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, STRATEGY_VERSION_ID,
                        USD_DEPOSIT, null, today, null, Instant.now(), null));

        service.rollIfDue(ctx, POST_BALANCE, CLOSING_PRICE, today);

        ArgumentCaptor<BigDecimal> poolLimitCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(cycleSnapshotCreator).createVrCycleAndSnapshot(
                any(), any(), any(), any(), any(), anyInt(), poolLimitCaptor.capture());
        // 1000.00 × 0.50 = 500.00
        assertThat(poolLimitCaptor.getValue()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(poolLimitCaptor.getValue().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("V′ ≤ 0 — 롤오버 보류, markEnded 미호출, 관리자+사용자 알림")
    void negativeNewValue_abortsRollover() {
        // evaluation=0 (holdings=0), pool=1, gradient=10, recurring=-9999 → V′ 음수 유도
        AccountBalance zeroHoldingsBalance = new AccountBalance(0, null, new BigDecimal("1.00"));
        // V=55, pool=1, G=10, recurring=-9999, evaluation=0
        // V′ = 55 + 1/10 + (-9999) + (0-55)/(2×√10) ≈ 55 + 0.1 - 9999 + ... 음수
        StrategyCycleVrDetail negVrDetail = new StrategyCycleVrDetail(
                CYCLE_ID, new BigDecimal("55.00"), 10, new BigDecimal("500.00"));
        // recurringAmount=-9999 → gradient=20, poolLimitRate=0.25
        StrategyVrDetail negDetail = new StrategyVrDetail(STRATEGY_VERSION_ID, 4, new BigDecimal("15.00"), -9999);

        LocalDate today = CYCLE_START.plusWeeks(4);
        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(negVrDetail));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(negDetail));

        service.rollIfDue(ctx, zeroHoldingsBalance, CLOSING_PRICE, today);

        verify(strategyCyclePort, never()).markEnded(any(), any(), any());
        verify(cycleSnapshotCreator, never()).createVrCycleAndSnapshot(any(), any(), any(), any(), any(), anyInt(), any());
        verify(notifyPort).notifyError(any(IllegalStateException.class)); // 관리자 알림
        verify(userNotificationPort).notifyError(eq(USER), any(IllegalStateException.class)); // 사용자 알림
    }

    @Test
    @DisplayName("적립식 bootstrap 매수 실패로 V가 0이면 새 사이클도 V=0으로 이어간다")
    void rollIfDue_recurringBootstrapFailed_rollsWithZeroValue() {
        StrategyCycleVrDetail zeroCycleVr = new StrategyCycleVrDetail(
                CYCLE_ID, BigDecimal.ZERO, 10, BigDecimal.ZERO);
        StrategyVrDetail depositDetail = new StrategyVrDetail(
                STRATEGY_VERSION_ID, 2, new BigDecimal("15.00"), 200);
        AccountBalance postBalance = new AccountBalance(0, null, BigDecimal.ZERO);
        LocalDate today = CYCLE_START.plusWeeks(2);

        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(zeroCycleVr));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(depositDetail));

        service.rollIfDue(ctx, postBalance, CLOSING_PRICE, today);

        verify(strategyCyclePort).markEnded(eq(CYCLE_ID), eq(BigDecimal.ZERO), eq(today));
        verify(cycleSnapshotCreator).createVrCycleAndSnapshot(
                eq(STRATEGY_ID), eq(STRATEGY_VERSION_ID), eq(postBalance), eq(CLOSING_PRICE),
                eq(BigDecimal.ZERO.setScale(2)), eq(10), eq(BigDecimal.ZERO.setScale(2)));
        verify(userNotificationPort, never()).notifyError(eq(USER), any(IllegalStateException.class));
    }

    @Test
    @DisplayName("closingPrice null — 롤오버 skip, markEnded 미호출, 관리자 알림")
    void nullClosingPrice_skipWithNotify() {
        LocalDate today = CYCLE_START.plusWeeks(4);
        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(CYCLE_VR));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(DETAIL));

        service.rollIfDue(ctx, POST_BALANCE, null, today);

        verify(strategyCyclePort, never()).markEnded(any(), any(), any());
        verify(cycleSnapshotCreator, never()).createVrCycleAndSnapshot(any(), any(), any(), any(), any(), anyInt(), any());
        verify(notifyPort).notifyError(any(IllegalStateException.class));
    }

    @Test
    @DisplayName("cycleVr 미존재 — notifyError 후 return, markEnded 미호출")
    void cycleVrNotFound_notifiesAndReturns() {
        LocalDate today = CYCLE_START.plusWeeks(4);
        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.empty());
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(DETAIL));

        service.rollIfDue(ctx, POST_BALANCE, CLOSING_PRICE, today);

        verify(strategyCyclePort, never()).markEnded(any(), any(), any());
        verify(notifyPort).notifyError(any(IllegalStateException.class));
    }

    @Test
    @DisplayName("holdings 승계 — 새 사이클에 postBalance(holdings=10, avgPrice=45) 그대로 전달")
    void holdingsInherited_inNewCycle() {
        LocalDate today = CYCLE_START.plusWeeks(4);
        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(CYCLE_VR));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(DETAIL));
        when(cycleSnapshotCreator.createVrCycleAndSnapshot(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, STRATEGY_VERSION_ID,
                        USD_DEPOSIT, null, today, null, Instant.now(), null));

        service.rollIfDue(ctx, POST_BALANCE, CLOSING_PRICE, today);

        // postBalance가 새 사이클 스냅샷에 그대로 전달됨 확인
        ArgumentCaptor<AccountBalance> balanceCaptor = ArgumentCaptor.forClass(AccountBalance.class);
        verify(cycleSnapshotCreator).createVrCycleAndSnapshot(
                any(), any(), balanceCaptor.capture(), any(), any(), anyInt(), any());
        AccountBalance captured = balanceCaptor.getValue();
        assertThat(captured.holdings()).isEqualTo(10);
        assertThat(captured.avgPrice()).isEqualByComparingTo(new BigDecimal("45.00"));
        assertThat(captured.usdDeposit()).isEqualByComparingTo(USD_DEPOSIT);
    }

    @Test
    @DisplayName("롤오버 완료 후 사용자 새 사이클 알림 발송")
    void afterRollover_notifiesUser() {
        LocalDate today = CYCLE_START.plusWeeks(4);
        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(CYCLE_VR));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(DETAIL));
        when(cycleSnapshotCreator.createVrCycleAndSnapshot(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, STRATEGY_VERSION_ID,
                        USD_DEPOSIT, null, today, null, Instant.now(), null));

        service.rollIfDue(ctx, POST_BALANCE, CLOSING_PRICE, today);

        verify(userNotificationPort).notifyNewCycleStarted(eq(USER), eq(ACCOUNT), eq(VR_STRATEGY), eq(USD_DEPOSIT));
    }
}
