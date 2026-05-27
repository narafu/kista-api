package com.kista.adapter.in.schedule;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.user.NotificationChannel;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.ExecuteTradingUseCase;
import com.kista.domain.port.in.ExecuteTradingUseCase.BatchContext;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.NotifyPort;
import com.kista.domain.port.out.TradingCyclePort;
import com.kista.domain.port.out.UserPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradingSchedulerTest {

    @Mock ExecuteTradingUseCase useCase;
    @Mock AccountPort accountPort;
    @Mock TradingCyclePort cyclePort;
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
                Account.Broker.KIS, Instant.now(), Instant.now());
    }

    private TradingCycle mockCycle() {
        return new TradingCycle(CYCLE_ID, ACCOUNT_ID, TradingCycle.Type.INFINITE,
                TradingCycle.Status.ACTIVE, TradingCycle.Ticker.SOXL, null,
                TradingCycle.CycleSeedType.NONE, Instant.now(), Instant.now());
    }

    private User mockUser() {
        return new User(USER_ID, "kakao-1", "홍길동", User.UserStatus.ACTIVE, User.UserRole.USER,
                null, null, null, Instant.now(), Instant.now(), null, NotificationChannel.TELEGRAM);
    }

    @Test
    void run_callsExecuteBatchWithAllContexts() throws InterruptedException {
        Account account = mockAccount();
        TradingCycle cycle = mockCycle();
        User user = mockUser();
        when(cyclePort.findAllActive()).thenReturn(List.of(cycle));
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(account);
        when(userPort.findById(USER_ID)).thenReturn(Optional.of(user));

        scheduler.run();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BatchContext>> captor = ArgumentCaptor.forClass(List.class);
        verify(useCase).executeBatch(captor.capture());
        assertThat(captor.getValue()).containsExactly(new BatchContext(cycle, account, user));
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
        TradingCycle cycle = mockCycle();
        User user = mockUser();
        when(cyclePort.findAllActive()).thenReturn(List.of(cycle));
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(account);
        when(userPort.findById(USER_ID)).thenReturn(Optional.of(user));
        doThrow(new InterruptedException("interrupted")).when(useCase).executeBatch(any());

        scheduler.run();

        assert Thread.currentThread().isInterrupted();
        Thread.interrupted(); // 플래그 초기화
    }

    @Test
    void run_contextBuildFails_skipsFailedCycleAndNotifiesAdmin() throws InterruptedException {
        // cycle1 계좌 조회 실패 → skip + notifyError, cycle2는 contexts에 포함되어 executeBatch 호출
        TradingCycle cycle1 = mockCycle();
        UUID cycleId2 = UUID.randomUUID();
        UUID accountId2 = UUID.randomUUID();
        TradingCycle cycle2 = new TradingCycle(cycleId2, accountId2, TradingCycle.Type.INFINITE,
                TradingCycle.Status.ACTIVE, TradingCycle.Ticker.TQQQ, null,
                TradingCycle.CycleSeedType.NONE, Instant.now(), Instant.now());
        Account account2 = new Account(accountId2, USER_ID, "계좌2",
                "99999999", "key2", "secret2", "01",
                Account.Broker.KIS, Instant.now(), Instant.now());
        User user = mockUser();

        when(cyclePort.findAllActive()).thenReturn(List.of(cycle1, cycle2));
        RuntimeException ex = new RuntimeException("계좌 없음");
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenThrow(ex);
        when(accountPort.findByIdOrThrow(accountId2)).thenReturn(account2);
        when(userPort.findById(USER_ID)).thenReturn(Optional.of(user));

        scheduler.run();

        verify(notifyPort).notifyError(ex);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BatchContext>> captor = ArgumentCaptor.forClass(List.class);
        verify(useCase).executeBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().getFirst().cycle()).isEqualTo(cycle2);
    }

    @Test
    void run_executeBatchException_notifiesAdminViaNotifyPort() throws InterruptedException {
        Account account = mockAccount();
        TradingCycle cycle = mockCycle();
        User user = mockUser();
        when(cyclePort.findAllActive()).thenReturn(List.of(cycle));
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(account);
        when(userPort.findById(USER_ID)).thenReturn(Optional.of(user));
        RuntimeException ex = new RuntimeException("KIS API 호출 실패");
        doThrow(ex).when(useCase).executeBatch(any());

        scheduler.run();

        verify(notifyPort).notifyError(ex);
    }
}
