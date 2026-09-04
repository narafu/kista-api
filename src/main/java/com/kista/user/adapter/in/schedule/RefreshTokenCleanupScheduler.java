package com.kista.user.adapter.in.schedule;

import com.kista.user.application.usecase.TokenUseCase;
import com.kista.platform.scheduling.SchedulerJobRunner;
import com.kista.platform.scheduling.SchedulerLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true) // local에서 끄면 운영 DB·텔레그램과 중복 알림 발생 방지
public class RefreshTokenCleanupScheduler {

    private final TokenUseCase tokenUseCase;
    private final SchedulerJobRunner jobRunner; // 스케쥴러 시작/종료 알림 (레거시 공통 골격 재사용)
    private final SchedulerLockService schedulerLockService;

    // 매일 04:00 KST — 만료된 RT 일괄 삭제
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void cleanupExpiredTokens() throws InterruptedException {
        schedulerLockService.tryRun("refresh-token-expired-cleanup", Duration.ofMinutes(30),
                () -> jobRunner.run("만료 RT 정리 스케쥴러", () -> {
                    int deleted = tokenUseCase.cleanupExpiredTokens();
                    log.info("만료 refresh_token {} 건 정리 완료", deleted);
                }));
    }

    // 매일 03:05 KST — grace 기간이 지난 회전 RT 일괄 삭제
    @Scheduled(cron = "0 5 3 * * *", zone = "Asia/Seoul")
    public void cleanupRotatedTokens() throws InterruptedException {
        schedulerLockService.tryRun("refresh-token-rotated-cleanup", Duration.ofMinutes(30),
                () -> jobRunner.run("회전 RT 정리 스케쥴러", () -> {
                    int deleted = tokenUseCase.cleanupRotatedTokens();
                    log.info("grace 초과 회전 refresh_token {} 건 정리 완료", deleted);
                }));
    }
}
