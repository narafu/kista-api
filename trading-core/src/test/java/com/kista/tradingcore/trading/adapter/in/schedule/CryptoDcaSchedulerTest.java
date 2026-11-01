package com.kista.tradingcore.trading.adapter.in.schedule;

import com.kista.shared.support.FixedClockTestConfig;
import com.kista.shared.platform.scheduling.SchedulerLockService;
import com.kista.tradingcore.trading.application.port.output.HeartbeatPort;
import com.kista.tradingcore.trading.application.usecase.CryptoDcaExecutionUseCase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.kista.shared.kernel.TimeZones;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CryptoDcaSchedulerTest {

    SchedulerLockService lock = mock(SchedulerLockService.class);
    CryptoDcaExecutionUseCase useCase = mock(CryptoDcaExecutionUseCase.class);
    HeartbeatPort heartbeat = mock(HeartbeatPort.class);
    CryptoDcaScheduler scheduler = new CryptoDcaScheduler(useCase, lock, heartbeat, FixedClockTestConfig.FIXED);

    @Test
    void lockNameAndDuration() throws Exception {
        scheduler.run();
        verify(lock).tryRun(eq("crypto-dca"), eq(Duration.ofMinutes(9)), any());
    }

    @Test
    void ticksWithKstNow() throws Exception {
        CryptoDcaScheduler scheduler = new CryptoDcaScheduler(useCase, lock, heartbeat,
                Clock.fixed(Instant.parse("2026-10-18T04:10:00Z"), TimeZones.KST));
        scheduler.run();
        var task = ArgumentCaptor.forClass(SchedulerLockService.LockedTask.class);
        verify(lock).tryRun(eq("crypto-dca"), any(), task.capture());
        task.getValue().run();
        verify(useCase).runTick(LocalDateTime.of(2026, 10, 18, 13, 10));
    }

    @Test
    void completedTick_pingsHeartbeat() throws Exception {
        when(lock.tryRun(eq("crypto-dca"), any(), any())).thenReturn(true);
        scheduler.run();
        verify(heartbeat).pingCrypto();
    }

    @Test
    void lockNotAcquired_noPing() throws Exception {
        when(lock.tryRun(eq("crypto-dca"), any(), any())).thenReturn(false);
        scheduler.run();
        verify(heartbeat, never()).pingCrypto();
    }

    @Test
    void tickFailure_propagatesWithoutPing() throws Exception {
        // 작업 예외는 tryRun 밖으로 전파된다 — 핑 전에 빠져나가야 한다
        when(lock.tryRun(eq("crypto-dca"), any(), any())).thenThrow(new IllegalStateException("boom"));
        assertThatThrownBy(scheduler::run).isInstanceOf(IllegalStateException.class);
        verify(heartbeat, never()).pingCrypto();
    }
}
