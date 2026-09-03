package com.kista.adapter.in.schedule;

import com.kista.application.port.output.MarketCalendarRefreshPort;
import com.kista.application.port.output.MarketHolidayStorePort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDate;

import static com.kista.common.TimeZones.KST;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketCalendarRefreshSchedulerTest {

    @Test
    void onStartup_skipsYearsThatAlreadyHaveData() throws Exception {
        MarketCalendarRefreshPort refreshPort = mock(MarketCalendarRefreshPort.class);
        MarketHolidayStorePort storePort = mock(MarketHolidayStorePort.class);
        SchedulerJobRunner jobRunner = mock(SchedulerJobRunner.class);
        SchedulerLockService lockService = mock(SchedulerLockService.class);
        MarketCalendarRefreshScheduler scheduler =
                new MarketCalendarRefreshScheduler(refreshPort, storePort, jobRunner, lockService);

        int year = LocalDate.now(KST).getYear();
        when(storePort.countByYear(year)).thenReturn(10L); // 당해 연도는 이미 적재됨
        when(storePort.countByYear(year + 1)).thenReturn(0L);
        when(storePort.countByYear(year + 2)).thenReturn(0L);

        scheduler.onStartup();

        ArgumentCaptor<SchedulerLockService.LockedTask> lockCaptor = ArgumentCaptor.forClass(SchedulerLockService.LockedTask.class);
        verify(lockService).tryRun(eq("market-calendar-startup"), eq(Duration.ofHours(1)), lockCaptor.capture());
        lockCaptor.getValue().run();

        ArgumentCaptor<Runnable> jobCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(jobRunner).run(eq("기동 시 캘린더 초기 적재"), jobCaptor.capture());
        jobCaptor.getValue().run();

        verify(refreshPort, never()).refreshCalendar(year);
        verify(refreshPort).refreshCalendar(year + 1);
        verify(refreshPort).refreshCalendar(year + 2);
    }

    @Test
    void onStartup_skipsJobRunnerEntirelyWhenAllYearsPresent() throws Exception {
        MarketCalendarRefreshPort refreshPort = mock(MarketCalendarRefreshPort.class);
        MarketHolidayStorePort storePort = mock(MarketHolidayStorePort.class);
        SchedulerJobRunner jobRunner = mock(SchedulerJobRunner.class);
        SchedulerLockService lockService = mock(SchedulerLockService.class);
        MarketCalendarRefreshScheduler scheduler =
                new MarketCalendarRefreshScheduler(refreshPort, storePort, jobRunner, lockService);

        int year = LocalDate.now(KST).getYear();
        when(storePort.countByYear(year)).thenReturn(10L);
        when(storePort.countByYear(year + 1)).thenReturn(5L);
        when(storePort.countByYear(year + 2)).thenReturn(1L);

        scheduler.onStartup();

        ArgumentCaptor<SchedulerLockService.LockedTask> lockCaptor = ArgumentCaptor.forClass(SchedulerLockService.LockedTask.class);
        verify(lockService).tryRun(eq("market-calendar-startup"), eq(Duration.ofHours(1)), lockCaptor.capture());
        lockCaptor.getValue().run();

        // 3년치 모두 존재 — jobRunner 자체를 호출하지 않아 텔레그램 알림도 발생하지 않음
        verify(jobRunner, never()).run(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Runnable.class));
        verify(refreshPort, never()).refreshCalendar(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void refreshForNewYear_alwaysRefreshesRegardlessOfExistingData() throws Exception {
        MarketCalendarRefreshPort refreshPort = mock(MarketCalendarRefreshPort.class);
        MarketHolidayStorePort storePort = mock(MarketHolidayStorePort.class);
        SchedulerJobRunner jobRunner = mock(SchedulerJobRunner.class);
        SchedulerLockService lockService = mock(SchedulerLockService.class);
        MarketCalendarRefreshScheduler scheduler =
                new MarketCalendarRefreshScheduler(refreshPort, storePort, jobRunner, lockService);

        int year = LocalDate.now(KST).getYear();

        scheduler.refreshForNewYear();

        ArgumentCaptor<SchedulerLockService.LockedTask> lockCaptor = ArgumentCaptor.forClass(SchedulerLockService.LockedTask.class);
        verify(lockService).tryRun(eq("market-calendar-yearly"), eq(Duration.ofHours(1)), lockCaptor.capture());
        lockCaptor.getValue().run();

        verify(jobRunner).run(eq("연간 캘린더 갱신 스케쥴러"), org.mockito.ArgumentMatchers.any(Runnable.class));
        // 연간 갱신 잡 내부 Runnable을 직접 실행해 존재 여부와 무관하게 3년치 모두 갱신되는지 검증
        ArgumentCaptor<Runnable> jobCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(jobRunner).run(eq("연간 캘린더 갱신 스케쥴러"), jobCaptor.capture());
        jobCaptor.getValue().run();

        verify(refreshPort).refreshCalendar(year);
        verify(refreshPort).refreshCalendar(year + 1);
        verify(refreshPort).refreshCalendar(year + 2);
        verify(storePort, never()).countByYear(org.mockito.ArgumentMatchers.anyInt());
    }
}
