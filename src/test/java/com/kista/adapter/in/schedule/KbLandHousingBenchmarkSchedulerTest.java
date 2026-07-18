package com.kista.adapter.in.schedule;

import com.kista.domain.port.in.FetchHousingBenchmarkUseCase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KbLandHousingBenchmarkSchedulerTest {

    @Test
    void run_acquiresLockAndRunsHousingBenchmarkFetchJob() throws Exception {
        FetchHousingBenchmarkUseCase useCase = mock(FetchHousingBenchmarkUseCase.class);
        SchedulerJobRunner jobRunner = mock(SchedulerJobRunner.class);
        SchedulerLockService lockService = mock(SchedulerLockService.class);
        KbLandHousingBenchmarkScheduler scheduler = new KbLandHousingBenchmarkScheduler(useCase, jobRunner, lockService);

        scheduler.run();

        ArgumentCaptor<SchedulerLockService.LockedTask> taskCaptor = ArgumentCaptor.forClass(SchedulerLockService.LockedTask.class);
        verify(lockService).tryRun(eq("kbland-housing-benchmark"), eq(Duration.ofMinutes(30)), taskCaptor.capture());

        taskCaptor.getValue().run();

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(jobRunner).run(eq("KB Land 주택 벤치마크 수집 스케쥴러"), runnableCaptor.capture());
        runnableCaptor.getValue().run();

        verify(useCase).fetchAndSave();
        assertThat(taskCaptor.getValue()).isNotNull();
    }
}
