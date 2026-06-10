package com.kista.adapter.in.schedule;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.BatchContext;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.port.in.TradingExecutionUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.NotifyPort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.UserPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradingSchedulerTest {

    @Mock TradingExecutionUseCase useCase;
    @Mock AccountPort accountPort;
    @Mock StrategyPort cyclePort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock UserPort userPort;
    @Mock NotifyPort notifyPort;
    @InjectMocks TradingScheduler scheduler;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID CYCLE_ID = UUID.randomUUID();

    // Account 10개 필드 생성자 (strategyType/strategyStatus/ticker/multiple 없음)
    private Account mockAccount() {
        return new Account(ACCOUNT_ID, USER_ID, "테스트계좌",
                "74420614", "key", "secret", "01",
                Account.Broker.KIS);
    }

    private Strategy mockStrategy() {
        return new Strategy(CYCLE_ID, ACCOUNT_ID, Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE);
    }

    private StrategyCycle mockStrategyCycle(UUID strategyId) {
        return new StrategyCycle(UUID.randomUUID(), strategyId, new BigDecimal("1000.00"),
                null, LocalDate.now(), null, Instant.now(), null);
    }

    private User mockUser() {
        return new User(USER_ID, "kakao-1", "홍길동", User.UserStatus.ACTIVE, User.UserRole.USER,
                null, null, null, null, NotificationChannel.TELEGRAM);
    }

    @Test
    void run_callsExecuteBatchWithAllContexts() throws InterruptedException {
        Account account = mockAccount();
        Strategy strategy = mockStrategy();
        StrategyCycle currentCycle = mockStrategyCycle(strategy.id());
        User user = mockUser();
        when(cyclePort.findAllActive()).thenReturn(List.of(strategy));
        when(strategyCyclePort.findLatestByStrategyId(strategy.id())).thenReturn(Optional.of(currentCycle));
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(account);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(user);

        scheduler.run();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BatchContext>> captor = ArgumentCaptor.forClass(List.class);
        verify(useCase).executeBatch(captor.capture());
        assertThat(captor.getValue()).containsExactly(new BatchContext(strategy, currentCycle, account, user));
    }

    @Test
    void run_noActiveStrategies_callsExecuteBatchWithEmptyList() throws InterruptedException {
        when(cyclePort.findAllActive()).thenReturn(List.of());

        scheduler.run();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BatchContext>> captor = ArgumentCaptor.forClass(List.class);
        verify(useCase).executeBatch(captor.capture());
        assertThat(captor.getValue()).isEmpty();
    }

    @Test
    void run_interruptedException_restoresInterruptFlag() throws InterruptedException {
        Account account = mockAccount();
        Strategy strategy = mockStrategy();
        StrategyCycle currentCycle = mockStrategyCycle(strategy.id());
        User user = mockUser();
        when(cyclePort.findAllActive()).thenReturn(List.of(strategy));
        when(strategyCyclePort.findLatestByStrategyId(strategy.id())).thenReturn(Optional.of(currentCycle));
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(account);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(user);
        doThrow(new InterruptedException("interrupted")).when(useCase).executeBatch(any());

        scheduler.run();

        assert Thread.currentThread().isInterrupted();
        Thread.interrupted(); // 플래그 초기화
    }

    @Test
    void run_contextBuildFails_skipsFailedCycleAndNotifiesAdmin() throws InterruptedException {
        // strategy1 계좌 조회 실패 → skip + notifyError, strategy2는 contexts에 포함되어 executeBatch 호출
        Strategy strategy1 = mockStrategy();
        UUID cycleId2 = UUID.randomUUID();
        UUID accountId2 = UUID.randomUUID();
        Strategy strategy2 = new Strategy(cycleId2, accountId2, Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Strategy.Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        StrategyCycle currentCycle2 = mockStrategyCycle(strategy2.id());
        Account account2 = new Account(accountId2, USER_ID, "계좌2",
                "99999999", "key2", "secret2", "01",
                Account.Broker.KIS);
        User user = mockUser();

        when(cyclePort.findAllActive()).thenReturn(List.of(strategy1, strategy2));
        RuntimeException ex = new RuntimeException("계좌 없음");
        // strategy1: 사이클 조회 성공하지만 계좌 조회 실패
        when(strategyCyclePort.findLatestByStrategyId(strategy1.id()))
                .thenReturn(Optional.of(mockStrategyCycle(strategy1.id())));
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenThrow(ex);
        // strategy2: 정상
        when(strategyCyclePort.findLatestByStrategyId(strategy2.id())).thenReturn(Optional.of(currentCycle2));
        when(accountPort.findByIdOrThrow(accountId2)).thenReturn(account2);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(user);

        scheduler.run();

        verify(notifyPort).notifyError(ex);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BatchContext>> captor = ArgumentCaptor.forClass(List.class);
        verify(useCase).executeBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().getFirst().strategy()).isEqualTo(strategy2);
    }

    @Test
    void run_executeBatchException_notifiesAdminViaNotifyPort() throws InterruptedException {
        Account account = mockAccount();
        Strategy strategy = mockStrategy();
        StrategyCycle currentCycle = mockStrategyCycle(strategy.id());
        User user = mockUser();
        when(cyclePort.findAllActive()).thenReturn(List.of(strategy));
        when(strategyCyclePort.findLatestByStrategyId(strategy.id())).thenReturn(Optional.of(currentCycle));
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(account);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(user);
        RuntimeException ex = new RuntimeException("KIS API 호출 실패");
        doThrow(ex).when(useCase).executeBatch(any());

        scheduler.run();

        verify(notifyPort).notifyError(ex);
    }
}
