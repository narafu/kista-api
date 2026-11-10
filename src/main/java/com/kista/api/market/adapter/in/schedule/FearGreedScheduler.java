package com.kista.api.market.adapter.in.schedule;

import com.kista.shared.platform.scheduling.SchedulerJobRunner;
import com.kista.shared.platform.scheduling.SchedulerLockService;
import com.kista.api.market.application.usecase.FetchFearGreedUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

// CNN 공포탐욕지수 수집 및 저장 (크립토는 조회 시 alternative.me 직접 호출)
// 07:00은 미국 정규장 마감(KST 05:00 DST / 06:00 비DST) 확정값, 12:00은 07:00 실패 대비 재수집(조회는 KST 일자별 최신 1건)
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true) // local에서 끄면 운영 DB·텔레그램과 중복 알림 발생 방지
public class FearGreedScheduler {

    private final FetchFearGreedUseCase fetchFearGreedUseCase;
    private final SchedulerJobRunner jobRunner;
    private final SchedulerLockService schedulerLockService;
    private final Clock clock; // 현재 시각 기준 — 테스트에서 Clock.fixed 주입

    @Scheduled(cron = "0 0 7,12 * * *", zone = "Asia/Seoul") // KST 07:00 / 12:00
    public void run() throws InterruptedException {
        schedulerLockService.tryRun("fear-greed", Duration.ofMinutes(30), this::runLocked);
    }

    private void runLocked() {
        jobRunner.run("공포탐욕지수 수집 스케쥴러", () -> fetchFearGreedUseCase.fetchAndSave(Instant.now(clock)));
    }
}
