package com.kista.adapter.in.schedule;

import com.kista.common.TimeZones;
import com.kista.domain.port.in.FetchHousingBenchmarkUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

// 매월 10일·20일 KST 07:00 KB Land 주택 벤치마크 수집 및 저장
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true)
public class KbLandHousingBenchmarkScheduler {

    private final FetchHousingBenchmarkUseCase fetchHousingBenchmarkUseCase;
    private final SchedulerJobRunner jobRunner;
    private final SchedulerLockService schedulerLockService;

    @Scheduled(cron = "0 0 7 10,20 * *", zone = TimeZones.KST_ID) // 매월 10일·20일 07:00 KST
    public void run() throws InterruptedException {
        schedulerLockService.tryRun("kbland-housing-benchmark", Duration.ofMinutes(30), this::runLocked);
    }

    private void runLocked() {
        jobRunner.run("KB Land 주택 벤치마크 수집 스케쥴러", fetchHousingBenchmarkUseCase::fetchAndSave);
    }
}
