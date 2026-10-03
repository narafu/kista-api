package com.kista.benchmark.adapter.in.schedule;

import com.kista.platform.scheduling.SchedulerJobRunner;
import com.kista.platform.scheduling.SchedulerLockService;
import com.kista.benchmark.application.usecase.SyncMarketIndexPricesUseCase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MarketIndexPriceSyncSchedulerTest {

    @Test
    void run_acquiresLockAndRunsMarketIndexPriceSyncJob() throws Exception {
        SyncMarketIndexPricesUseCase useCase = mock(SyncMarketIndexPricesUseCase.class);
        SchedulerJobRunner jobRunner = mock(SchedulerJobRunner.class);
        SchedulerLockService lockService = mock(SchedulerLockService.class);
        MarketIndexPriceSyncScheduler scheduler = new MarketIndexPriceSyncScheduler(useCase, jobRunner, lockService);

        scheduler.run();

        ArgumentCaptor<SchedulerLockService.LockedTask> taskCaptor = ArgumentCaptor.forClass(SchedulerLockService.LockedTask.class);
        verify(lockService).tryRun(eq("market-index-price-sync"), eq(Duration.ofMinutes(30)), taskCaptor.capture());

        taskCaptor.getValue().run();

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(jobRunner).run(eq("벤치마크 ETF 지수 종가 동기화 스케쥴러"), runnableCaptor.capture());
        runnableCaptor.getValue().run();

        verify(useCase).syncAndSave();
        assertThat(taskCaptor.getValue()).isNotNull();
    }

    @Test
    void runNow_sharesCronLockAndRunsManualJob() throws Exception {
        SyncMarketIndexPricesUseCase useCase = mock(SyncMarketIndexPricesUseCase.class);
        SchedulerJobRunner jobRunner = mock(SchedulerJobRunner.class);
        SchedulerLockService lockService = mock(SchedulerLockService.class);
        MarketIndexPriceSyncScheduler scheduler = new MarketIndexPriceSyncScheduler(useCase, jobRunner, lockService);

        scheduler.runNow();

        // 크론과 같은 락 이름 — 수동 실행과 09:00 크론이 겹치지 않음
        ArgumentCaptor<SchedulerLockService.LockedTask> taskCaptor = ArgumentCaptor.forClass(SchedulerLockService.LockedTask.class);
        verify(lockService).tryRun(eq("market-index-price-sync"), eq(Duration.ofMinutes(30)), taskCaptor.capture());
        taskCaptor.getValue().run();

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(jobRunner).run(eq("벤치마크 ETF 지수 종가 동기화 스케쥴러 수동"), runnableCaptor.capture());
        runnableCaptor.getValue().run();

        verify(useCase).syncAndSave();
    }
}
