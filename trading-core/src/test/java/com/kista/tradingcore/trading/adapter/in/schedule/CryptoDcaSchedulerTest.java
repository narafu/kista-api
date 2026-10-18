package com.kista.tradingcore.trading.adapter.in.schedule;

import com.kista.shared.platform.scheduling.SchedulerLockService;
import com.kista.tradingcore.trading.application.usecase.CryptoDcaExecutionUseCase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CryptoDcaSchedulerTest {

    SchedulerLockService lock = mock(SchedulerLockService.class);
    CryptoDcaExecutionUseCase useCase = mock(CryptoDcaExecutionUseCase.class);
    CryptoDcaScheduler scheduler = new CryptoDcaScheduler(useCase, lock);

    @Test
    void lockNameAndDuration() throws Exception {
        scheduler.run();
        verify(lock).tryRun(eq("crypto-dca"), eq(Duration.ofMinutes(9)), any());
    }

    @Test
    void ticksWithKstNow() throws Exception {
        scheduler.clock = Clock.fixed(Instant.parse("2026-10-18T04:10:00Z"), ZoneOffset.UTC);
        scheduler.run();
        var task = ArgumentCaptor.forClass(SchedulerLockService.LockedTask.class);
        verify(lock).tryRun(eq("crypto-dca"), any(), task.capture());
        task.getValue().run();
        verify(useCase).runTick(LocalDateTime.of(2026, 10, 18, 13, 10));
    }
}
