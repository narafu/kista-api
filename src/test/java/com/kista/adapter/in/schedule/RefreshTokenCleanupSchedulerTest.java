package com.kista.adapter.in.schedule;

import com.kista.domain.port.in.TokenUseCase;
import com.kista.domain.port.out.NotifyPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenCleanupSchedulerTest {

    @Mock TokenUseCase tokenUseCase;
    @Mock NotifyPort notifyPort;
    @Mock SchedulerLockService schedulerLockService;

    RefreshTokenCleanupScheduler scheduler;

    @BeforeEach
    void setUp() throws InterruptedException {
        scheduler = new RefreshTokenCleanupScheduler(tokenUseCase, notifyPort, schedulerLockService);

        // 락을 항상 획득한 것으로 간주 — 전달된 LockedTask를 즉시 실행하는 pass-through stub
        lenient().doAnswer((Answer<Boolean>) invocation -> {
            SchedulerLockService.LockedTask task = invocation.getArgument(2);
            task.run();
            return true;
        }).when(schedulerLockService).tryRun(any(), any(), any());
    }

    @Test
    void cleanupExpiredTokens_정상_실행시_유스케이스_호출과_시작완료_알림() throws InterruptedException {
        // 정상 케이스: 만료 RT 정리 유스케이스가 호출되고 시작/완료 notifyInfo가 각각 1회씩 호출됨
        when(tokenUseCase.cleanupExpiredTokens()).thenReturn(3);

        scheduler.cleanupExpiredTokens();

        verify(tokenUseCase).cleanupExpiredTokens();
        verify(notifyPort).notifyInfo("만료 RT 정리 스케쥴러 시작");
        verify(notifyPort).notifyInfo("만료 RT 정리 스케쥴러 완료");
        verify(notifyPort, never()).notifyError(any());
    }

    @Test
    void cleanupExpiredTokens_유스케이스_예외_발생시_notifyError_호출후_완료알림까지_도달() throws InterruptedException {
        // 정리 로직 내부 예외는 cleanupExpiredTokensLocked의 try/catch가 흡수 — 완료 notifyInfo까지 정상 도달, 예외는 밖으로 전파되지 않음
        RuntimeException failure = new RuntimeException("DB 커넥션 실패");
        when(tokenUseCase.cleanupExpiredTokens()).thenThrow(failure);

        scheduler.cleanupExpiredTokens();

        verify(notifyPort).notifyInfo("만료 RT 정리 스케쥴러 시작");
        verify(notifyPort).notifyError(failure);
        verify(notifyPort).notifyInfo("만료 RT 정리 스케쥴러 완료");
    }

    @Test
    void cleanupRotatedTokens_정상_실행시_유스케이스_호출과_시작완료_알림() throws InterruptedException {
        // 회전 RT 정리도 동일 구조 — grace 초과 회전 RT 정리 유스케이스 호출 + 시작/완료 알림
        when(tokenUseCase.cleanupRotatedTokens()).thenReturn(5);

        scheduler.cleanupRotatedTokens();

        verify(tokenUseCase).cleanupRotatedTokens();
        verify(notifyPort).notifyInfo("회전 RT 정리 스케쥴러 시작");
        verify(notifyPort).notifyInfo("회전 RT 정리 스케쥴러 완료");
        verify(notifyPort, never()).notifyError(any());
    }
}
