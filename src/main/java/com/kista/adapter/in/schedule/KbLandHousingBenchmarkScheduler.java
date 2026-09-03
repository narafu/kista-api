package com.kista.adapter.in.schedule;

import com.kista.common.TimeZones;
import com.kista.application.usecase.FetchHousingBenchmarkUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

// 매주 일요일 KST 07:00 KB Land 주택 벤치마크 수집 및 저장
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true)
public class KbLandHousingBenchmarkScheduler {

    private final FetchHousingBenchmarkUseCase fetchHousingBenchmarkUseCase;
    private final SchedulerJobRunner jobRunner;
    private final SchedulerLockService schedulerLockService;

    @Scheduled(cron = "0 0 8 * * SAT", zone = TimeZones.KST_ID) // 매주 토요일 08:00 KST
    public void run() throws InterruptedException {
        schedulerLockService.tryRun("kbland-housing-benchmark", Duration.ofMinutes(30), this::runLocked);
    }

    // 수동 트리거 — 크론 대기 없이 즉시 실행. run()과 락 이름을 공유해 크론과 동시 실행되지 않음
    public void runNow() throws InterruptedException {
        schedulerLockService.tryRun("kbland-housing-benchmark", Duration.ofMinutes(30),
                () -> jobRunner.run("KB Land 주택 벤치마크 수집 스케쥴러 수동", fetchHousingBenchmarkUseCase::fetchAndSave));
    }

    private void runLocked() {
        jobRunner.run("KB Land 주택 벤치마크 수집 스케쥴러", fetchHousingBenchmarkUseCase::fetchAndSave);
    }
}
