package com.kista.adapter.in.schedule;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.privacy.PrivacyTradeValidationReport;
import com.kista.domain.model.strategy.BatchContext;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.port.in.TradingExecutionUseCase;
import com.kista.domain.port.out.*;
import com.kista.domain.port.in.PrivacyTradeValidationUseCase;
import org.junit.jupiter.api.BeforeEach;
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
class TradingOpenSchedulerTest {

    @Mock TradingExecutionUseCase useCase;
    @Mock AccountPort accountPort;
    @Mock StrategyPort strategyPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock UserPort userPort;
    @Mock NotifyPort notifyPort;
    @Mock SchedulerLockService schedulerLockService;
    @Mock PrivacyTradePort privacyTradePort;
    @Mock PrivacyTradeValidationUseCase validationService;
    @InjectMocks TradingOpenScheduler scheduler;

    private static final UUID USER_ID    = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    private Account mockAccount(UUID accountId) {
        return new Account(accountId, USER_ID, "테스트계좌",
                "74420614", "key", "secret", null, Account.Broker.KIS, null);
    }

    private Strategy mockStrategy(UUID accountId, Strategy.Type type) {
        return new Strategy(UUID.randomUUID(), accountId, type,
                Strategy.Status.ACTIVE, Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE);
    }

    private StrategyCycle mockCycle(UUID strategyId) {
        return new StrategyCycle(UUID.randomUUID(), strategyId, new BigDecimal("1000.00"),
                null, LocalDate.now(), null, Instant.now(), null);
    }

    private User mockUser() {
        return new User(USER_ID, "kakao-1", "홍길동", User.UserStatus.ACTIVE, User.UserRole.USER,
                null, null, null, null, NotificationChannel.TELEGRAM);
    }

    @BeforeEach
    void setUp() throws InterruptedException {
        lenient().doAnswer(invocation -> {
            SchedulerLockService.LockedTask task = invocation.getArgument(2);
            task.run();
            return true;
        }).when(schedulerLockService).tryRun(any(), any(), any());
    }

    @Test
    void run_includesBothInfiniteAndPrivacyStrategies() throws InterruptedException {
        // INFINITE + PRIVACY 모두 포함 — 장 개시 스케쥴러 전략 타입 불문 모두 처리
        Strategy infinite = mockStrategy(ACCOUNT_ID, Strategy.Type.INFINITE);
        Strategy privacy  = mockStrategy(ACCOUNT_ID, Strategy.Type.PRIVACY);
        Account account   = mockAccount(ACCOUNT_ID);
        User user         = mockUser();

        when(strategyPort.findAllActive()).thenReturn(List.of(infinite, privacy));
        when(strategyCyclePort.findLatestByStrategyId(infinite.id())).thenReturn(Optional.of(mockCycle(infinite.id())));
        when(strategyCyclePort.findLatestByStrategyId(privacy.id())).thenReturn(Optional.of(mockCycle(privacy.id())));
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(account);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(user);

        scheduler.run();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BatchContext>> captor = ArgumentCaptor.forClass(List.class);
        verify(useCase).placeOpenOrders(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().stream().map(c -> c.strategy().type()).toList())
                .containsExactlyInAnyOrder(Strategy.Type.INFINITE, Strategy.Type.PRIVACY);
    }

    @Test
    void run_noActiveStrategies_callsPlaceOpenOrdersWithEmptyList() throws InterruptedException {
        when(strategyPort.findAllActive()).thenReturn(List.of());

        scheduler.run();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BatchContext>> captor = ArgumentCaptor.forClass(List.class);
        verify(useCase).placeOpenOrders(captor.capture());
        assertThat(captor.getValue()).isEmpty();
    }

    @Test
    void run_contextBuildFails_skipsFailedStrategyAndNotifiesAdmin() throws InterruptedException {
        // strategy1 계좌 조회 실패 → skip + notifyError, strategy2는 포함
        Strategy strategy1 = mockStrategy(ACCOUNT_ID, Strategy.Type.INFINITE);
        UUID accountId2    = UUID.randomUUID();
        Strategy strategy2 = mockStrategy(accountId2, Strategy.Type.PRIVACY);
        Account account2   = mockAccount(accountId2);
        User user          = mockUser();

        when(strategyPort.findAllActive()).thenReturn(List.of(strategy1, strategy2));
        when(strategyCyclePort.findLatestByStrategyId(strategy1.id())).thenReturn(Optional.of(mockCycle(strategy1.id())));
        when(strategyCyclePort.findLatestByStrategyId(strategy2.id())).thenReturn(Optional.of(mockCycle(strategy2.id())));
        RuntimeException ex = new RuntimeException("계좌 없음");
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenThrow(ex);
        when(accountPort.findByIdOrThrow(accountId2)).thenReturn(account2);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(user);

        scheduler.run();

        verify(notifyPort).notifyError(ex);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BatchContext>> captor = ArgumentCaptor.forClass(List.class);
        verify(useCase).placeOpenOrders(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().getFirst().strategy()).isEqualTo(strategy2);
    }

    @Test
    void run_interruptedException_restoresInterruptFlag() throws InterruptedException {
        Strategy strategy = mockStrategy(ACCOUNT_ID, Strategy.Type.INFINITE);
        Account account   = mockAccount(ACCOUNT_ID);
        User user         = mockUser();

        when(strategyPort.findAllActive()).thenReturn(List.of(strategy));
        when(strategyCyclePort.findLatestByStrategyId(strategy.id())).thenReturn(Optional.of(mockCycle(strategy.id())));
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(account);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(user);
        doThrow(new InterruptedException("interrupted")).when(useCase).placeOpenOrders(any());

        scheduler.run();

        assert Thread.currentThread().isInterrupted();
        Thread.interrupted(); // 플래그 초기화
    }

    @Test
    void run_placeOpenOrdersException_notifiesAdmin() throws InterruptedException {
        Strategy strategy = mockStrategy(ACCOUNT_ID, Strategy.Type.INFINITE);
        Account account   = mockAccount(ACCOUNT_ID);
        User user         = mockUser();

        when(strategyPort.findAllActive()).thenReturn(List.of(strategy));
        when(strategyCyclePort.findLatestByStrategyId(strategy.id())).thenReturn(Optional.of(mockCycle(strategy.id())));
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(account);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(user);
        RuntimeException ex = new RuntimeException("KIS API 오류");
        doThrow(ex).when(useCase).placeOpenOrders(any());

        scheduler.run();

        verify(notifyPort).notifyError(ex);
    }

    @Test
    void run_lockNotAcquired_skipsSchedulerBody() throws InterruptedException {
        doReturn(false).when(schedulerLockService).tryRun(any(), any(), any());

        scheduler.run();

        verifyNoInteractions(strategyPort, useCase, notifyPort);
    }

    @Test
    void run_invalidPrivacyBase_pausesPrivacyStrategiesAndSkipsThem() throws InterruptedException {
        Strategy infinite = mockStrategy(ACCOUNT_ID, Strategy.Type.INFINITE);
        UUID privacyAccountId = UUID.randomUUID();
        Strategy privacy = mockStrategy(privacyAccountId, Strategy.Type.PRIVACY);
        Account infiniteAccount = mockAccount(ACCOUNT_ID);
        Account privacyAccount = mockAccount(privacyAccountId);
        User user = mockUser();
        PrivacyTradeBase invalidBase = new PrivacyTradeBase(UUID.randomUUID(), new BigDecimal("225.75"),
                4, new BigDecimal("13977.43"), List.of());

        when(strategyPort.findAllActive()).thenReturn(List.of(infinite, privacy));
        when(privacyTradePort.findTodayTrade(any())).thenReturn(Optional.of(invalidBase));
        when(validationService.inspect(invalidBase))
                .thenReturn(PrivacyTradeValidationReport.warning("MISSING_SELL", "SELL 주문이 없습니다"));
        when(strategyCyclePort.findLatestByStrategyId(infinite.id())).thenReturn(Optional.of(mockCycle(infinite.id())));
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(infiniteAccount);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(user);

        scheduler.run();

        verify(strategyPort, never()).save(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BatchContext>> captor = ArgumentCaptor.forClass(List.class);
        verify(useCase).placeOpenOrders(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().getFirst().strategy().type()).isEqualTo(Strategy.Type.INFINITE);
        verify(notifyPort).notifyError(any(IllegalStateException.class));
    }
}
