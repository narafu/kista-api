package com.kista.adapter.in.schedule;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.ExecuteTradingUseCase;
import com.kista.domain.port.out.AccountRepository;
import com.kista.domain.port.out.NotifyPort;
import com.kista.domain.port.out.TradingCycleRepository;
import com.kista.domain.port.out.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradingSchedulerTest {

    @Mock ExecuteTradingUseCase useCase;
    @Mock AccountRepository accountRepository;
    @Mock TradingCycleRepository cycleRepository;
    @Mock UserRepository userRepository;
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
                TradingCycle.Status.ACTIVE, TradingCycle.Ticker.SOXL, BigDecimal.ONE,
                null, Instant.now(), Instant.now());
    }

    private User mockUser() {
        return new User(USER_ID, "kakao-1", "홍길동", User.UserStatus.ACTIVE, User.UserRole.USER,
                null, null, null, Instant.now(), Instant.now(), null);
    }

    @Test
    void run_callsExecuteForEachActiveStrategy() throws InterruptedException {
        Account account = mockAccount();
        TradingCycle cycle = mockCycle();
        User user = mockUser();
        when(cycleRepository.findAllActive()).thenReturn(List.of(cycle));
        when(accountRepository.findByIdOrThrow(ACCOUNT_ID)).thenReturn(account);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        scheduler.run();

        verify(useCase).execute(cycle, account, user);
    }

    @Test
    void run_noActiveStrategies_doesNotCallExecute() throws InterruptedException {
        when(cycleRepository.findAllActive()).thenReturn(List.of());

        scheduler.run();

        verify(useCase, never()).execute(any(), any(), any());
    }

    @Test
    void run_interruptedException_restoresInterruptFlag() throws InterruptedException {
        Account account = mockAccount();
        TradingCycle cycle = mockCycle();
        User user = mockUser();
        when(cycleRepository.findAllActive()).thenReturn(List.of(cycle));
        when(accountRepository.findByIdOrThrow(ACCOUNT_ID)).thenReturn(account);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        doThrow(new InterruptedException("interrupted")).when(useCase).execute(any(), any(), any());

        scheduler.run();

        assert Thread.currentThread().isInterrupted();
        Thread.interrupted(); // 플래그 초기화
    }

    @Test
    void run_unexpectedException_continuesNextCycle() throws InterruptedException {
        Account account = mockAccount();
        TradingCycle cycle1 = mockCycle();
        TradingCycle cycle2 = new TradingCycle(UUID.randomUUID(), ACCOUNT_ID,
                TradingCycle.Type.INFINITE, TradingCycle.Status.ACTIVE,
                TradingCycle.Ticker.SOXL, BigDecimal.ONE,
                null, Instant.now(), Instant.now());
        User user = mockUser();
        when(cycleRepository.findAllActive()).thenReturn(List.of(cycle1, cycle2));
        when(accountRepository.findByIdOrThrow(ACCOUNT_ID)).thenReturn(account);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        doThrow(new RuntimeException("api error")).when(useCase).execute(eq(cycle1), any(), any());

        scheduler.run();

        verify(useCase).execute(eq(cycle2), any(), any());
    }

    @Test
    void run_unexpectedException_notifiesAdminViaNotifyPort() throws InterruptedException {
        Account account = mockAccount();
        TradingCycle cycle = mockCycle();
        User user = mockUser();
        when(cycleRepository.findAllActive()).thenReturn(List.of(cycle));
        when(accountRepository.findByIdOrThrow(ACCOUNT_ID)).thenReturn(account);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        RuntimeException ex = new RuntimeException("KIS API 호출 실패");
        doThrow(ex).when(useCase).execute(any(), any(), any());

        scheduler.run();

        verify(notifyPort).notifyError(ex);
    }
}
