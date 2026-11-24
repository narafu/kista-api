package com.kista.api.user.adapter.in.schedule;

import com.kista.api.user.application.service.TokenService;
import com.kista.shared.platform.scheduling.SchedulerJobRunner;
import com.kista.shared.platform.scheduling.SchedulerLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenCleanupSchedulerTest {

    @Mock TokenService tokenService;
    @Mock SchedulerJobRunner jobRunner;
    @Mock SchedulerLockService schedulerLockService;

    RefreshTokenCleanupScheduler scheduler;

    @BeforeEach
    void setUp() throws InterruptedException {
        scheduler = new RefreshTokenCleanupScheduler(tokenService, jobRunner, schedulerLockService);

        lenient().doAnswer((Answer<Boolean>) invocation -> {
            SchedulerLockService.LockedTask task = invocation.getArgument(2);
            task.run();
            return true;
        }).when(schedulerLockService).tryRun(any(), any(), any());
    }

    @Test
    void cleanupExpiredTokens_jobRunner에게_이름과_작업을_위임한다() throws InterruptedException {
        scheduler.cleanupExpiredTokens();

        ArgumentCaptor<Runnable> jobCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(jobRunner).run(eq("만료 RT 정리 스케쥴러"), jobCaptor.capture());

        when(tokenService.cleanupExpiredTokens()).thenReturn(3);
        jobCaptor.getValue().run();
        verify(tokenService).cleanupExpiredTokens();
    }
}
