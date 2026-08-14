package com.kista.adapter.in.schedule;

import com.kista.common.TimeZones;
import com.kista.domain.port.in.FetchHousingPriceIndexUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

// 매주 일요일 KST 07:10 KB Land 주간 아파트 매매가격지수 수집 및 저장 (5분위 배치와 10분 stagger)
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true)
public class KbLandPriceIndexScheduler {

    private final FetchHousingPriceIndexUseCase fetchHousingPriceIndexUseCase;
    private final SchedulerJobRunner jobRunner;
    private final SchedulerLockService schedulerLockService;

    @Scheduled(cron = "0 10 8 * * SAT", zone = TimeZones.KST_ID) // 매주 토요일 08:10 KST
    public void run() throws InterruptedException {
        schedulerLockService.tryRun("kbland-price-index", Duration.ofMinutes(30), this::runLocked);
    }

    // 수동 트리거 — 크론 대기 없이 즉시 실행. run()과 락 이름을 공유해 크론과 동시 실행되지 않음
    public void runNow() throws InterruptedException {
        schedulerLockService.tryRun("kbland-price-index", Duration.ofMinutes(30),
                () -> jobRunner.run("KB Land 주간 아파트 매매가격지수 수집 스케쥴러 수동", fetchHousingPriceIndexUseCase::fetchAndSave));
    }

    private void runLocked() {
        jobRunner.run("KB Land 주간 아파트 매매가격지수 수집 스케쥴러", fetchHousingPriceIndexUseCase::fetchAndSave);
    }
}
