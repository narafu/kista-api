package com.kista.adapter.in.schedule;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.BatchContext;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.TradingExecutionUseCase;
import com.kista.domain.port.out.HeartbeatPort;
import com.kista.domain.port.out.NotifyPort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradingCloseSchedulerTest {

    @Mock TradingExecutionUseCase useCase;
    @Mock StrategyPort strategyPort;
    @Mock SchedulerLockService schedulerLockService;
    @Mock BatchContextFactory contextFactory;
    @Mock NotifyPort notifyPort;
    @Mock HeartbeatPort heartbeatPort;

    TradingCloseScheduler scheduler;

    private static final UUID USER_ID    = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID CYCLE_ID   = UUID.randomUUID();

    private Account mockAccount() {
        return DomainFixtures.kisAccount(ACCOUNT_ID, USER_ID);
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
        return DomainFixtures.activeUser(USER_ID);
    }

    @BeforeEach
    void setUp() throws InterruptedException {
        // SchedulerJobRunner는 실제 인스턴스로 생성 — 실행 골격(인터럽트/예외 처리)까지 검증
        SchedulerJobRunner jobRunner = new SchedulerJobRunner(notifyPort);
        scheduler = new TradingCloseScheduler(useCase, strategyPort, schedulerLockService, contextFactory, jobRunner, heartbeatPort);

        lenient().doAnswer((Answer<Boolean>) invocation -> {
            SchedulerLockService.LockedTask task = invocation.getArgument(2);
            try {
                task.run();
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }).when(schedulerLockService).tryRun(any(), any(), any());
    }

    @Test
    void run_callsExecuteBatchWithAllContexts() throws InterruptedException {
        Strategy strategy = mockStrategy();
        StrategyCycle cycle = mockStrategyCycle(strategy.id());
        Account account = mockAccount();
        User user = mockUser();
        BatchContext context = new BatchContext(strategy, cycle, account, user);

        when(strategyPort.findAllActive()).thenReturn(List.of(strategy));
        when(contextFactory.buildAll(List.of(strategy))).thenReturn(List.of(context));

        scheduler.run();

        verify(useCase).executeBatch(List.of(context));
        verify(heartbeatPort).pingClose();
    }

    @Test
    void run_noActiveStrategies_callsExecuteBatchWithEmptyList() throws InterruptedException {
        when(strategyPort.findAllActive()).thenReturn(List.of());
        when(contextFactory.buildAll(List.of())).thenReturn(List.of());

        scheduler.run();

        verify(useCase).executeBatch(List.of());
        verify(heartbeatPort).pingClose();
    }

    @Test
    void run_interruptedException_restoresInterruptFlag() throws InterruptedException {
        Strategy strategy = mockStrategy();
        BatchContext context = new BatchContext(strategy, mockStrategyCycle(strategy.id()), mockAccount(), mockUser());

        when(strategyPort.findAllActive()).thenReturn(List.of(strategy));
        when(contextFactory.buildAll(any())).thenReturn(List.of(context));
        doAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new InterruptedException("interrupted");
        }).when(useCase).executeBatch(anyList());

        try {
            scheduler.run();
        } catch (InterruptedException e) {
            // 인터럽트 플래그 복원 확인
        }

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted(); // 플래그 초기화
        verify(heartbeatPort, never()).pingClose(); // 인터럽트 시 핑 도달 안 함
    }

    @Test
    void run_executeBatchException_notifiesAdminViaNotifyPort() throws InterruptedException {
        Strategy strategy = mockStrategy();
        BatchContext context = new BatchContext(strategy, mockStrategyCycle(strategy.id()), mockAccount(), mockUser());
        RuntimeException ex = new RuntimeException("KIS API 호출 실패");

        when(strategyPort.findAllActive()).thenReturn(List.of(strategy));
        when(contextFactory.buildAll(any())).thenReturn(List.of(context));
        doThrow(ex).when(useCase).executeBatch(any());

        scheduler.run();

        verify(notifyPort).notifyError(ex);
        verify(heartbeatPort).pingClose(); // jobRunner가 RuntimeException을 삼키므로 runLocked는 정상 종료 — 핑 도달
    }

    @Test
    void run_lockNotAcquired_skipsSchedulerBody() throws InterruptedException {
        doReturn(false).when(schedulerLockService).tryRun(any(), any(), any());

        scheduler.run();

        verifyNoInteractions(strategyPort, contextFactory, useCase, notifyPort, heartbeatPort);
    }
}
