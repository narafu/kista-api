package com.kista.tradingcore.trading.adapter.in.schedule;

import com.kista.shared.platform.scheduling.SchedulerJobRunner;
import com.kista.shared.platform.scheduling.SchedulerLifecycleEvent;
import com.kista.shared.platform.scheduling.SchedulerLockService;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.marketcalendar.domain.model.MarketSessionTimes;
import com.kista.tradingcore.trading.application.port.output.TradingBatchRunPort;
import com.kista.tradingcore.trading.domain.model.TradingBatchPhase;
import com.kista.tradingcore.trading.domain.model.TradingAccount;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.domain.model.TradingUserProfile;
import com.kista.tradingcore.trading.application.usecase.TradingExecutionUseCase;
import com.kista.tradingcore.trading.application.port.output.HeartbeatPort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.application.port.output.TradingErrorReportPort;
import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.support.TradingFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyCycleSeedType;

@ExtendWith(MockitoExtension.class)
class TradingCloseSchedulerTest {

    @Mock TradingExecutionUseCase useCase;
    @Mock StrategyPort strategyPort;
    @Mock SchedulerLockService schedulerLockService;
    @Mock BatchContextFactory contextFactory;
    @Mock ApplicationEventPublisher events;
    @Mock HeartbeatPort heartbeatPort;
    @Mock TradingErrorReportPort errorReportPort;
    @Mock MarketCalendarPort calendar;
    @Mock TradingBatchRunPort batchRunPort;
    @Mock TaskScheduler taskScheduler;
    @Mock ScheduledFuture<?> future;
    @Mock Clock clock;

    Instant now = Instant.parse("2026-10-06T19:30:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 7);

    TradingCloseScheduler scheduler;

    private static final UUID USER_ID    = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID CYCLE_ID   = UUID.randomUUID();

    private TradingAccount mockAccount() {
        return TradingFixtures.tradingAccount(ACCOUNT_ID, USER_ID);
    }

    private Strategy mockStrategy() {
        return new Strategy(CYCLE_ID, ACCOUNT_ID, StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
    }

    private StrategyCycle mockStrategyCycle(UUID strategyId) {
        return new StrategyCycle(UUID.randomUUID(), strategyId, new BigDecimal("1000.00"),
                null, LocalDate.now(), null, Instant.now(), null);
    }

    private TradingUserProfile mockUser() {
        return TradingFixtures.tradingUserProfile(USER_ID);
    }

    @BeforeEach
    void setUp() throws InterruptedException {
        // SchedulerJobRunner는 실제 인스턴스로 생성 — 실행 골격(인터럽트/예외 처리)까지 검증
        SchedulerJobRunner jobRunner = new SchedulerJobRunner(events);
        scheduler = new TradingCloseScheduler(useCase, strategyPort, schedulerLockService, contextFactory, jobRunner,
                heartbeatPort, errorReportPort, clock, calendar, batchRunPort, taskScheduler,
                new TradingBatchSerialExecutor());
        lenient().when(clock.withZone(TimeZones.KST)).thenReturn(clock);
        lenient().when(clock.getZone()).thenReturn(TimeZones.KST);
        lenient().when(clock.instant()).thenAnswer(_ -> now);
        lenient().when(calendar.findSession(any())).thenAnswer(invocation -> {
            LocalDate date = invocation.getArgument(0);
            int closeHour = date.getMonthValue() == 12 ? 6 : 5;
            return Optional.of(new MarketSessionTimes(date.minusDays(1).atTime(22, 30).atZone(TimeZones.KST).toInstant(),
                    date.atTime(closeHour, 0).atZone(TimeZones.KST).toInstant()));
        });

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
        // 재개 경로(takeOver)도 같은 방식으로 task 즉시 실행
        lenient().doAnswer((Answer<Boolean>) invocation -> {
            SchedulerLockService.LockedTask task = invocation.getArgument(2);
            task.run();
            return true;
        }).when(schedulerLockService).takeOver(any(), any(), any());
    }

    private void fire() {
        now = Instant.parse("2026-10-06T19:00:00Z");
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        scheduler.schedule(DATE);
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(callback.capture(), any(Instant.class));
        now = Instant.parse("2026-10-06T19:30:00Z");
        callback.getValue().run();
    }

    private void runNow(ZonedDateTime time) throws InterruptedException {
        now = time.toInstant();
        scheduler.runNow();
    }

    @Test
    void run_callsExecuteBatchWithAllContexts() throws InterruptedException {
        Strategy strategy = mockStrategy();
        StrategyCycle cycle = mockStrategyCycle(strategy.id());
        TradingAccount account = mockAccount();
        TradingUserProfile user = mockUser();
        BatchContext context = new BatchContext(strategy, cycle, account, user);

        when(strategyPort.findAllActive()).thenReturn(List.of(strategy));
        when(contextFactory.buildAll(List.of(strategy))).thenReturn(List.of(context));

        fire();

        verify(useCase).executeBatchNow(List.of(context));
        verify(heartbeatPort).pingClose();
    }

    @Test
    void run_addsPausedStrategiesWithOrders_activeWinsOnDuplicate() throws InterruptedException {
        Strategy active = mockStrategy();
        Strategy pausedOnly = new Strategy(UUID.randomUUID(), ACCOUNT_ID, StrategyType.INFINITE,
                StrategyStatus.PAUSED, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        Strategy pausedDuplicate = active.withStatus(StrategyStatus.PAUSED); // 두 쿼리 사이 정지된 같은 전략
        when(strategyPort.findAllActive()).thenReturn(List.of(active));
        when(strategyPort.findNotActiveWithReportableOrders(DATE))
                .thenReturn(List.of(pausedDuplicate, pausedOnly));
        when(contextFactory.buildAll(List.of(active, pausedOnly))).thenReturn(List.of());

        fire();

        verify(contextFactory).buildAll(List.of(active, pausedOnly));
    }

    @Test
    void run_noActiveStrategies_callsExecuteBatchWithEmptyList() throws InterruptedException {
        when(strategyPort.findAllActive()).thenReturn(List.of());
        when(contextFactory.buildAll(List.of())).thenReturn(List.of());

        fire();

        verify(useCase).executeBatchNow(List.of());
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
        }).when(useCase).executeBatchNow(anyList());

        fire();

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted(); // 플래그 초기화
        verify(heartbeatPort, never()).pingClose(); // 인터럽트 시 핑 도달 안 함
    }

    @Test
    void run_executeBatchException_publishesFailedLifecycleEvent() throws InterruptedException {
        Strategy strategy = mockStrategy();
        BatchContext context = new BatchContext(strategy, mockStrategyCycle(strategy.id()), mockAccount(), mockUser());
        RuntimeException ex = new RuntimeException("KIS API 호출 실패");

        when(strategyPort.findAllActive()).thenReturn(List.of(strategy));
        when(contextFactory.buildAll(any())).thenReturn(List.of(context));
        doThrow(ex).when(useCase).executeBatchNow(any());

        fire();

        // notify 직접 호출 대신 FAILED 생명주기 이벤트 발행 — 이벤트는 예외 identity가 아닌 message만 담는다
        ArgumentCaptor<SchedulerLifecycleEvent> captor = ArgumentCaptor.forClass(SchedulerLifecycleEvent.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues().get(1).phase()).isEqualTo(SchedulerLifecycleEvent.Phase.FAILED);
        assertThat(captor.getAllValues().get(1).errorMessage()).isEqualTo("KIS API 호출 실패");
        verify(heartbeatPort, never()).pingClose(); // 실패한 배치는 완료 신호를 보내지 않는다 — dead-man's switch가 감지
    }

    @Test
    void runNow_insidePlacementWindow_runsImmediateBatch() throws InterruptedException {
        // DST 2026-10-07(수) 04:40 KST — 장마감 05:00, 접수 마감 04:50 전
        when(strategyPort.findAllActive()).thenReturn(List.of());
        when(contextFactory.buildAll(any())).thenReturn(List.of());

        runNow(ZonedDateTime.of(2026, 10, 7, 4, 40, 0, 0, TimeZones.KST));

        verify(useCase).executeBatchNow(List.of());
        verifyNoInteractions(errorReportPort);
    }

    @Test
    void runNow_afterPlacementCutoff_rejectsWithAdminAlert() throws InterruptedException {
        // DST 04:55 KST — 접수 마감(04:50) 경과: LOC가 다음 세션으로 넘어가므로 거부
        runNow(ZonedDateTime.of(2026, 10, 7, 4, 55, 0, 0, TimeZones.KST));

        verify(errorReportPort).reportError(any(IllegalStateException.class));
        verifyNoInteractions(schedulerLockService, useCase);
    }

    @Test
    void runNow_windowBoundaries_dstAndNonDst() throws InterruptedException {
        lenient().when(strategyPort.findAllActive()).thenReturn(List.of());
        lenient().when(contextFactory.buildAll(any())).thenReturn(List.of());
        // 경계 포함 판정: 시작(04:30) 포함, 접수 마감(장마감-10분) 미포함 — DST 장마감 05:00, 비DST 06:00
        runNow(ZonedDateTime.of(2026, 10, 7, 4, 30, 0, 0, TimeZones.KST));
        runNow(ZonedDateTime.of(2026, 12, 2, 5, 49, 0, 0, TimeZones.KST));
        runNow(ZonedDateTime.of(2026, 10, 7, 4, 50, 0, 0, TimeZones.KST));
        runNow(ZonedDateTime.of(2026, 12, 2, 5, 50, 0, 0, TimeZones.KST));

        verify(useCase, times(2)).executeBatchNow(List.of());
        verify(errorReportPort, times(2)).reportError(any(IllegalStateException.class));
    }

    @Test
    void runNow_eveningBeforeCron_rejects() throws InterruptedException {
        // 23:00 KST — 다음 거래일 세션이라 당일 KST 거래일 기준 마감 배치와 어긋난다
        runNow(ZonedDateTime.of(2026, 10, 7, 23, 0, 0, 0, TimeZones.KST));
        runNow(ZonedDateTime.of(2026, 10, 7, 3, 20, 0, 0, TimeZones.KST)); // 마감(05:00) 90분 전 이전

        verify(errorReportPort, times(2)).reportError(any(IllegalStateException.class));
        verifyNoInteractions(schedulerLockService, useCase);
    }

    @Test
    void run_lockNotAcquired_skipsSchedulerBody() throws InterruptedException {
        doReturn(false).when(schedulerLockService).tryRun(any(), any(), any());

        fire();

        verifyNoInteractions(strategyPort, contextFactory, useCase, events, heartbeatPort);
    }

    @Test
    void resume_takesOverCloseLock_runsFullBatch() throws InterruptedException {
        when(strategyPort.findAllActive()).thenReturn(List.of());
        when(contextFactory.buildAll(any())).thenReturn(List.of());

        scheduler.resume();

        verify(schedulerLockService).takeOver(eq("trading-close"), eq(Duration.ofHours(3)), any());
        verify(useCase).executeBatchNow(List.of());
        verify(heartbeatPort).pingClose();
    }

    @Test
    void resumeReport_takesOverCloseLock_runsReportOnly() throws InterruptedException {
        when(strategyPort.findAllActive()).thenReturn(List.of());
        when(contextFactory.buildAll(any())).thenReturn(List.of());
        when(batchRunPort.findPhase(any(), eq(DATE))).thenReturn(Optional.of(TradingBatchPhase.PLACED));

        scheduler.resumeReport();

        verify(useCase).resumeCloseReport(List.of());
        verify(useCase, never()).executeBatch(any());
        verify(heartbeatPort).pingClose();
    }

    @Test
    void schedule_usesActualEarlyCloseAndSuppressesDuplicates() {
        now = Instant.parse("2026-10-06T13:00:00Z");
        Instant close = Instant.parse("2026-10-06T17:00:00Z");
        when(calendar.findSession(DATE)).thenReturn(Optional.of(new MarketSessionTimes(now, close)));
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        scheduler.schedule(DATE);
        scheduler.schedule(DATE);

        verify(taskScheduler).schedule(any(Runnable.class), eq(close.minus(Duration.ofMinutes(30))));
    }

    @Test
    void reconcile_changedCloseReschedulesAndPastTodayIsSilent() {
        now = Instant.parse("2026-10-06T13:00:00Z"); // 10-06 22:00 KST — 오늘(10-06) 예약 시각은 이미 지남
        Instant close = Instant.parse("2026-10-06T20:00:00Z");
        Instant earlyClose = Instant.parse("2026-10-06T17:00:00Z");
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        scheduler.schedule(DATE);
        doReturn(Optional.of(new MarketSessionTimes(now, earlyClose))).when(calendar).findSession(DATE);

        scheduler.reconcile();

        verify(future).cancel(false);
        verify(taskScheduler).schedule(any(Runnable.class), eq(close.minus(Duration.ofMinutes(30))));
        verify(taskScheduler).schedule(any(Runnable.class), eq(earlyClose.minus(Duration.ofMinutes(30))));
        verifyNoInteractions(errorReportPort);
    }

    @Test
    void callback_firesSlightlyEarly_stillRuns() throws InterruptedException {
        now = Instant.parse("2026-10-06T19:00:00Z");
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        scheduler.schedule(DATE);
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(callback.capture(), any(Instant.class));
        now = Instant.parse("2026-10-06T19:29:30Z"); // 예약 시각(19:30Z)보다 30초 일찍 발화

        callback.getValue().run();

        verify(useCase).executeBatchNow(any());
        verifyNoInteractions(errorReportPort);
    }

    @Test
    void schedule_holidayAndDoneDoNotRegister() {
        now = Instant.parse("2026-10-06T13:00:00Z");
        LocalDate holiday = DATE.plusDays(1);
        when(calendar.findSession(holiday)).thenReturn(Optional.empty());
        when(batchRunPort.findPhase(any(), eq(DATE))).thenReturn(Optional.of(TradingBatchPhase.DONE));

        scheduler.schedule(holiday);
        scheduler.schedule(DATE);

        verifyNoInteractions(taskScheduler, errorReportPort);
    }

    @Test
    void schedule_missingDataAndRejectedRegistrationAlert() {
        now = Instant.parse("2026-10-06T13:00:00Z");
        when(calendar.findSession(DATE)).thenThrow(new IllegalStateException("missing"));
        scheduler.schedule(DATE);
        doReturn(Optional.of(new MarketSessionTimes(now, now.plusSeconds(3600)))).when(calendar).findSession(DATE); // 위 thenThrow stub 재호출 방지
        doThrow(new IllegalStateException("rejected")).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        scheduler.schedule(DATE);

        verify(errorReportPort, times(2)).reportError(any(IllegalStateException.class));
    }

    @Test
    void callback_afterCutoffAlertsWithoutExecuting() {
        now = Instant.parse("2026-10-06T19:00:00Z");
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        scheduler.schedule(DATE);
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(callback.capture(), any(Instant.class));
        now = Instant.parse("2026-10-06T19:50:00Z");

        callback.getValue().run();

        verify(errorReportPort).reportError(any(IllegalStateException.class));
        verifyNoInteractions(useCase, heartbeatPort);
    }

    @Test
    void callback_doneAfterReservationDoesNotRun() {
        now = Instant.parse("2026-10-06T19:00:00Z");
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        scheduler.schedule(DATE);
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(callback.capture(), any(Instant.class));
        when(batchRunPort.findPhase(any(), eq(DATE))).thenReturn(Optional.of(TradingBatchPhase.DONE));
        now = Instant.parse("2026-10-06T19:30:00Z");

        callback.getValue().run();

        verifyNoInteractions(useCase, heartbeatPort);
    }

    @Test
    void callback_exceptionIsReportedToAdmin() throws InterruptedException {
        now = Instant.parse("2026-10-06T19:00:00Z");
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        scheduler.schedule(DATE);
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(callback.capture(), any(Instant.class));
        doThrow(new IllegalStateException("lock failed")).when(schedulerLockService).tryRun(any(), any(), any());

        callback.getValue().run();

        verify(errorReportPort).reportError(any(IllegalStateException.class));
    }

    // 04:30 하트비트 슬롯 — now는 KST DATE 04:30

    @Test
    void heartbeatSlot_holidayWithoutSession_pings() {
        doReturn(Optional.empty()).when(calendar).findSession(DATE);

        scheduler.pingHeartbeatSlot();

        verify(heartbeatPort).pingClose();
    }

    @Test
    void heartbeatSlot_earlyCloseBatchAlreadyDone_pings() {
        when(batchRunPort.findPhase(any(), eq(DATE))).thenReturn(Optional.of(TradingBatchPhase.DONE));

        scheduler.pingHeartbeatSlot();

        verify(heartbeatPort).pingClose();
    }

    @Test
    void heartbeatSlot_regularDayBatchStillPending_doesNotPing() {
        // 비DST 05:30 배치 전 — 배치 완료 핑이 대신 간다
        when(batchRunPort.findPhase(any(), eq(DATE))).thenReturn(Optional.of(TradingBatchPhase.PLACED));

        scheduler.pingHeartbeatSlot();

        verify(heartbeatPort, never()).pingClose();
    }

    @Test
    void heartbeatSlot_calendarNotLoaded_doesNotPingOrThrow() {
        doThrow(new IllegalStateException("시장 세션 미적재")).when(calendar).findSession(DATE);

        scheduler.pingHeartbeatSlot();

        verify(heartbeatPort, never()).pingClose();
    }
}
