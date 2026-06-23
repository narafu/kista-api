package com.kista.adapter.in.schedule;

import com.kista.domain.port.in.TokenUseCase;
import com.kista.domain.port.out.NotifyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenCleanupScheduler {

    private final TokenUseCase tokenUseCase;
    private final NotifyPort notifyPort; // 스케쥴러 시작/종료 알림
    private final SchedulerLockService schedulerLockService;

    // 매일 03:00 KST — 만료된 RT 일괄 삭제
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    public void cleanupExpiredTokens() throws InterruptedException {
        schedulerLockService.tryRun("refresh-token-expired-cleanup", Duration.ofMinutes(30), this::cleanupExpiredTokensLocked);
    }

    private void cleanupExpiredTokensLocked() {
        notifyPort.notifyInfo("만료 RT 정리 스케쥴러 시작");
        int deleted = tokenUseCase.cleanupExpiredTokens();
        log.info("만료 refresh_token {} 건 정리 완료", deleted);
        notifyPort.notifyInfo("만료 RT 정리 스케쥴러 완료");
    }

    // 매일 03:05 KST — grace 기간이 지난 회전 RT 일괄 삭제
    @Scheduled(cron = "0 5 3 * * *", zone = "Asia/Seoul")
    public void cleanupRotatedTokens() throws InterruptedException {
        schedulerLockService.tryRun("refresh-token-rotated-cleanup", Duration.ofMinutes(30), this::cleanupRotatedTokensLocked);
    }

    private void cleanupRotatedTokensLocked() {
        notifyPort.notifyInfo("회전 RT 정리 스케쥴러 시작");
        int deleted = tokenUseCase.cleanupRotatedTokens();
        log.info("grace 초과 회전 refresh_token {} 건 정리 완료", deleted);
        notifyPort.notifyInfo("회전 RT 정리 스케쥴러 완료");
    }
}
