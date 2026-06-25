package com.kista.adapter.in.schedule;

import com.kista.common.TimeZones;
import com.kista.domain.port.in.FetchFearGreedUseCase;
import com.kista.domain.port.out.NotifyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;

// 매일 09:10 KST — 크립토·CNN 공포탐욕지수 수집 및 저장
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true) // local에서 끄면 운영 DB·텔레그램과 중복 알림 발생 방지
public class FearGreedScheduler {

    private final FetchFearGreedUseCase fetchFearGreedUseCase;
    private final NotifyPort notifyPort; // 스케쥴러 시작/종료 알림
    private final SchedulerLockService schedulerLockService;

    @Scheduled(cron = "0 0 10 * * *", zone = TimeZones.KST_ID) // 매일 10:00 KST
    public void run() throws InterruptedException {
        schedulerLockService.tryRun("fear-greed-daily", Duration.ofMinutes(30), this::runLocked);
    }

    private void runLocked() {
        notifyPort.notifyInfo("공포탐욕지수 수집 스케쥴러 시작");
        LocalDate today = LocalDate.now(TimeZones.KST);
        log.info("공포탐욕지수 수집 스케쥴러 시작 (date={})", today);
        try {
            fetchFearGreedUseCase.fetchAndSave(today);
        } catch (Exception e) {
            log.error("공포탐욕지수 수집 실패: {}", e.getMessage(), e);
            notifyPort.notifyError(e);
        }
        log.info("공포탐욕지수 수집 스케쥴러 완료");
        notifyPort.notifyInfo("공포탐욕지수 수집 스케쥴러 완료");
    }
}
