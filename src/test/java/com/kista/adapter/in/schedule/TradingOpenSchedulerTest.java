package com.kista.adapter.in.schedule;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.privacy.PrivacyTradeValidationReport;
import com.kista.domain.model.strategy.BatchContext;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.TradingExecutionUseCase;
import com.kista.domain.port.out.*;
import com.kista.domain.port.in.PrivacyTradeValidationUseCase;
import com.kista.domain.port.out.HeartbeatPort;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyList;

@ExtendWith(MockitoExtension.class)
class TradingOpenSchedulerTest {

    @Mock TradingExecutionUseCase useCase;
    @Mock StrategyPort strategyPort;
    @Mock SchedulerLockService schedulerLockService;
    @Mock PrivacyTradePort privacyTradePort;
    @Mock PrivacyTradeValidationUseCase validationService;
    @Mock BatchContextFactory contextFactory;
    @Mock NotifyPort notifyPort;
    @Mock HeartbeatPort heartbeatPort;

    TradingOpenScheduler scheduler;

    private static final UUID USER_ID    = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    private Account mockAccount(UUID accountId) {
        return DomainFixtures.kisAccount(accountId, USER_ID);
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
        return DomainFixtures.activeUserWithTelegram(USER_ID);
    }

    @BeforeEach
    void setUp() throws InterruptedException {
        // SchedulerJobRunner는 실제 인스턴스로 생성 — 실행 골격(인터럽트/예외 처리)까지 검증
        SchedulerJobRunner jobRunner = new SchedulerJobRunner(notifyPort);
        scheduler = new TradingOpenScheduler(useCase, strategyPort, notifyPort, schedulerLockService,
                privacyTradePort, validationService, contextFactory, jobRunner, heartbeatPort);

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
    void run_includesBothInfiniteAndPrivacyStrategies() throws InterruptedException {
        // INFINITE + PRIVACY 모두 포함 — 장 개시 스케쥴러 전략 타입 불문 모두 처리
        Strategy infinite = mockStrategy(ACCOUNT_ID, Strategy.Type.INFINITE);
        Strategy privacy  = mockStrategy(ACCOUNT_ID, Strategy.Type.PRIVACY);
        Account account   = mockAccount(ACCOUNT_ID);
        User user         = mockUser();
        BatchContext infiniteCtx = new BatchContext(infinite, mockCycle(infinite.id()), account, user);
        BatchContext privacyCtx  = new BatchContext(privacy,  mockCycle(privacy.id()),  account, user);

        when(strategyPort.findAllActive()).thenReturn(List.of(infinite, privacy));
        // PRIVACY 가드: 기준 매매표 없음 → 필터 없이 통과
        when(privacyTradePort.findTodayTrade(any())).thenReturn(Optional.empty());
        when(contextFactory.buildAll(List.of(infinite, privacy))).thenReturn(List.of(infiniteCtx, privacyCtx));

        scheduler.run();

        verify(useCase).placeOpenOrders(List.of(infiniteCtx, privacyCtx));
        verify(heartbeatPort).pingOpen();
    }

    @Test
    void run_noActiveStrategies_callsPlaceOpenOrdersWithEmptyList() throws InterruptedException {
        when(strategyPort.findAllActive()).thenReturn(List.of());
        when(contextFactory.buildAll(List.of())).thenReturn(List.of());

        scheduler.run();

        verify(useCase).placeOpenOrders(List.of());
        verify(heartbeatPort).pingOpen();
    }

    @Test
    void run_interruptedException_restoresInterruptFlag() throws InterruptedException {
        Strategy strategy = mockStrategy(ACCOUNT_ID, Strategy.Type.INFINITE);
        BatchContext context = new BatchContext(strategy, mockCycle(strategy.id()), mockAccount(ACCOUNT_ID), mockUser());

        when(strategyPort.findAllActive()).thenReturn(List.of(strategy));
        // INFINITE만 있으면 guardPrivacyStrategies 조기 반환 — privacyTradePort 호출 없음
        when(contextFactory.buildAll(any())).thenReturn(List.of(context));
        doAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new InterruptedException("interrupted");
        }).when(useCase).placeOpenOrders(anyList());

        try {
            scheduler.run();
        } catch (InterruptedException e) {
            // 인터럽트 플래그 복원 확인
        }

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted(); // 플래그 초기화
        verify(heartbeatPort, never()).pingOpen(); // 인터럽트 시 핑 도달 안 함
    }

    @Test
    void run_placeOpenOrdersException_notifiesAdmin() throws InterruptedException {
        Strategy strategy = mockStrategy(ACCOUNT_ID, Strategy.Type.INFINITE);
        BatchContext context = new BatchContext(strategy, mockCycle(strategy.id()), mockAccount(ACCOUNT_ID), mockUser());
        RuntimeException ex = new RuntimeException("KIS API 오류");

        when(strategyPort.findAllActive()).thenReturn(List.of(strategy));
        // INFINITE만 있으면 guardPrivacyStrategies 조기 반환 — privacyTradePort 호출 없음
        when(contextFactory.buildAll(any())).thenReturn(List.of(context));
        doThrow(ex).when(useCase).placeOpenOrders(any());

        scheduler.run();

        verify(notifyPort).notifyError(ex);
        verify(heartbeatPort).pingOpen(); // jobRunner가 RuntimeException을 삼키므로 runLocked는 정상 종료 — 핑 도달
    }

    @Test
    void run_lockNotAcquired_skipsSchedulerBody() throws InterruptedException {
        doReturn(false).when(schedulerLockService).tryRun(any(), any(), any());

        scheduler.run();

        verifyNoInteractions(strategyPort, contextFactory, useCase, notifyPort, heartbeatPort);
    }

    @Test
    void run_invalidPrivacyBase_pausesPrivacyStrategiesAndSkipsThem() throws InterruptedException {
        Strategy infinite = mockStrategy(ACCOUNT_ID, Strategy.Type.INFINITE);
        UUID privacyAccountId = UUID.randomUUID();
        Strategy privacy = mockStrategy(privacyAccountId, Strategy.Type.PRIVACY);
        Account infiniteAccount = mockAccount(ACCOUNT_ID);
        User user = mockUser();
        BatchContext infiniteCtx = new BatchContext(infinite, mockCycle(infinite.id()), infiniteAccount, user);
        PrivacyTradeBase invalidBase = new PrivacyTradeBase(UUID.randomUUID(), new BigDecimal("225.75"),
                4, new BigDecimal("13977.43"), List.of());

        when(strategyPort.findAllActive()).thenReturn(List.of(infinite, privacy));
        when(privacyTradePort.findTodayTrade(any())).thenReturn(Optional.of(invalidBase));
        when(validationService.inspect(invalidBase))
                .thenReturn(PrivacyTradeValidationReport.warning("MISSING_SELL", "SELL 주문이 없습니다"));
        // 가드 후 INFINITE만 남아 contextFactory에 전달됨
        when(contextFactory.buildAll(List.of(infinite))).thenReturn(List.of(infiniteCtx));

        scheduler.run();

        verify(strategyPort, never()).save(any());
        verify(useCase).placeOpenOrders(List.of(infiniteCtx));
        verify(notifyPort).notifyError(any(IllegalStateException.class));
        verify(heartbeatPort).pingOpen();
    }
}
