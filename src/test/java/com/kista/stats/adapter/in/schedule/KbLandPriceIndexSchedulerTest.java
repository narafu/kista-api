package com.kista.stats.adapter.in.schedule;

import com.kista.adapter.in.schedule.SchedulerJobRunner;
import com.kista.adapter.in.schedule.SchedulerLockService;
import com.kista.stats.application.usecase.FetchHousingPriceIndexUseCase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KbLandPriceIndexSchedulerTest {

    @Test
    void run_acquiresLockAndRunsRecentYearsFetchJob() throws Exception {
        FetchHousingPriceIndexUseCase useCase = mock(FetchHousingPriceIndexUseCase.class);
        SchedulerJobRunner jobRunner = mock(SchedulerJobRunner.class);
        SchedulerLockService lockService = mock(SchedulerLockService.class);
        KbLandPriceIndexScheduler scheduler = new KbLandPriceIndexScheduler(useCase, jobRunner, lockService);

        scheduler.run();

        // 5분위 배치와 별도 락 이름 사용 — 대용량 주간 지수 배치가 소용량 배치에 무음 차단되지 않도록 함
        ArgumentCaptor<SchedulerLockService.LockedTask> taskCaptor = ArgumentCaptor.forClass(SchedulerLockService.LockedTask.class);
        verify(lockService).tryRun(eq("kbland-price-index"), eq(Duration.ofMinutes(30)), taskCaptor.capture());

        taskCaptor.getValue().run();

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(jobRunner).run(eq("KB Land 주간 아파트 매매가격지수 수집 스케쥴러"), runnableCaptor.capture());
        runnableCaptor.getValue().run();

        // 매주 배치는 최근 2년치만 가볍게 갱신한다.
        verify(useCase).fetchAndSave(2);
        assertThat(taskCaptor.getValue()).isNotNull();
    }

    @Test
    void runFullRefresh_acquiresSeparateLockAndRunsTwentyYearFetchJob() throws Exception {
        FetchHousingPriceIndexUseCase useCase = mock(FetchHousingPriceIndexUseCase.class);
        SchedulerJobRunner jobRunner = mock(SchedulerJobRunner.class);
        SchedulerLockService lockService = mock(SchedulerLockService.class);
        KbLandPriceIndexScheduler scheduler = new KbLandPriceIndexScheduler(useCase, jobRunner, lockService);

        scheduler.runFullRefresh();

        // 매주 배치와 별도 락 이름 사용 — 월간 풀 리프레시가 매주 배치를 차단하지 않도록 함
        ArgumentCaptor<SchedulerLockService.LockedTask> taskCaptor = ArgumentCaptor.forClass(SchedulerLockService.LockedTask.class);
        verify(lockService).tryRun(eq("kbland-price-index-full"), eq(Duration.ofMinutes(30)), taskCaptor.capture());

        taskCaptor.getValue().run();

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(jobRunner).run(eq("KB Land 주간 아파트 매매가격지수 월간 풀 리프레시 스케쥴러"), runnableCaptor.capture());
        runnableCaptor.getValue().run();

        // 월간 풀 리프레시는 20년 전체를 다시 받아 과거 구간의 KB Land 보정을 반영한다.
        verify(useCase).fetchAndSave(20);
    }
}
