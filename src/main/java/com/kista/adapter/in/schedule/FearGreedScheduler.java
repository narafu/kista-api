package com.kista.adapter.in.schedule;

import com.kista.domain.port.in.FetchFearGreedUseCase;
import com.kista.domain.port.out.NotifyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

// UTC 6시간마다(00:00 / 06:00 / 12:00 / 18:00) 크립토·CNN 공포탐욕지수 수집 및 저장
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true) // local에서 끄면 운영 DB·텔레그램과 중복 알림 발생 방지
public class FearGreedScheduler {

    private final FetchFearGreedUseCase fetchFearGreedUseCase;
    private final NotifyPort notifyPort; // 스케쥴러 시작/종료 알림
    private final SchedulerLockService schedulerLockService;

    @Scheduled(cron = "0 0 0,6,12,18 * * *", zone = "UTC") // UTC 00:00 / 06:00 / 12:00 / 18:00
    public void run() throws InterruptedException {
        schedulerLockService.tryRun("fear-greed-6hourly", Duration.ofMinutes(30), this::runLocked);
    }

    private void runLocked() {
        notifyPort.notifyInfo("공포탐욕지수 수집 스케쥴러 시작");
        Instant snapshotDate = Instant.now();
        log.info("공포탐욕지수 수집 스케쥴러 시작 (snapshotDate={})", snapshotDate);
        try {
            fetchFearGreedUseCase.fetchAndSave(snapshotDate);
        } catch (Exception e) {
            log.error("공포탐욕지수 수집 실패: {}", e.getMessage(), e);
            notifyPort.notifyError(e);
        }
        log.info("공포탐욕지수 수집 스케쥴러 완료");
        notifyPort.notifyInfo("공포탐욕지수 수집 스케쥴러 완료");
    }
}
