package com.kista.adapter.in.schedule;

import com.kista.domain.model.*;
import com.kista.domain.port.in.ExecuteTradingUseCase;
import com.kista.domain.port.out.AccountRepository;
import com.kista.domain.port.out.NotifyPort;
import com.kista.domain.port.out.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradingSchedulerTest {

    @Mock ExecuteTradingUseCase useCase;
    @Mock AccountRepository accountRepository;
    @Mock UserRepository userRepository;
    @Mock NotifyPort notifyPort;
    @InjectMocks TradingScheduler scheduler;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    private Account mockAccount() {
        return new Account(ACCOUNT_ID, USER_ID, "테스트계좌",
                "74420614", "key", "secret", "01",
                StrategyType.INFINITE, StrategyStatus.ACTIVE,
                null, null, "SOXL", "AMS", Instant.now(), Instant.now());
    }

    private User mockUser() {
        return new User(USER_ID, "kakao-1", "홍길동", UserStatus.ACTIVE,
                null, null, Instant.now(), Instant.now(), null);
    }

    @Test
    void run_callsExecuteForEachActiveAccount() throws InterruptedException {
        Account account = mockAccount();
        User user = mockUser();
        when(accountRepository.findAllActive()).thenReturn(List.of(account));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        scheduler.run();

        verify(useCase).execute(account, user);
    }

    @Test
    void run_noActiveAccounts_doesNotCallExecute() throws InterruptedException {
        when(accountRepository.findAllActive()).thenReturn(List.of());

        scheduler.run();

        verify(useCase, never()).execute(any(), any());
    }

    @Test
    void run_interruptedException_restoresInterruptFlag() throws InterruptedException {
        Account account = mockAccount();
        User user = mockUser();
        when(accountRepository.findAllActive()).thenReturn(List.of(account));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        doThrow(new InterruptedException("interrupted")).when(useCase).execute(any(), any());

        scheduler.run();

        assert Thread.currentThread().isInterrupted();
        Thread.interrupted(); // 플래그 초기화
    }

    @Test
    void run_unexpectedException_continuesNextAccount() throws InterruptedException {
        Account account1 = mockAccount();
        Account account2 = new Account(UUID.randomUUID(), USER_ID, "계좌2",
                "74420615", "key2", "secret2", "01",
                StrategyType.INFINITE, StrategyStatus.ACTIVE,
                null, null, "SOXL", "AMS", Instant.now(), Instant.now());
        User user = mockUser();
        when(accountRepository.findAllActive()).thenReturn(List.of(account1, account2));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        doThrow(new RuntimeException("api error")).when(useCase).execute(eq(account1), any());

        scheduler.run();

        // account1 실패해도 account2 실행 확인
        verify(useCase).execute(eq(account2), any());
    }

    @Test
    void run_unexpectedException_notifiesAdminViaNotifyPort() throws InterruptedException {
        Account account = mockAccount();
        User user = mockUser();
        when(accountRepository.findAllActive()).thenReturn(List.of(account));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        RuntimeException ex = new RuntimeException("KIS API 호출 실패");
        doThrow(ex).when(useCase).execute(any(), any());

        scheduler.run();

        verify(notifyPort).notifyError(ex);
    }
}
