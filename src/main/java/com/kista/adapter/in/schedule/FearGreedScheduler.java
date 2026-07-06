package com.kista.adapter.in.schedule;

import com.kista.domain.port.in.FetchFearGreedUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

// UTC 12시간마다(06:00 / 18:00) 크립토·CNN 공포탐욕지수 수집 및 저장
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true) // local에서 끄면 운영 DB·텔레그램과 중복 알림 발생 방지
public class FearGreedScheduler {

    private final FetchFearGreedUseCase fetchFearGreedUseCase;
    private final SchedulerJobRunner jobRunner;
    private final SchedulerLockService schedulerLockService;

    @Scheduled(cron = "0 0 6,18 * * *", zone = "UTC") // UTC 06:00 / 18:00
    public void run() throws InterruptedException {
        schedulerLockService.tryRun("fear-greed-12hourly", Duration.ofMinutes(30), this::runLocked);
    }

    private void runLocked() {
        jobRunner.run("공포탐욕지수 수집 스케쥴러", () -> fetchFearGreedUseCase.fetchAndSave(Instant.now()));
    }
}
