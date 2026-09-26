package com.kista.stats.adapter.in.schedule;

import com.kista.platform.scheduling.SchedulerJobRunner;
import com.kista.platform.scheduling.SchedulerLockService;
import com.kista.sharedkernel.TimeZones;
import com.kista.stats.application.usecase.FetchHousingPriceIndexUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

// 매주 토요일 KST 08:10 KB Land 주간 아파트 매매가격지수 수집 및 저장 (5분위 배치와 10분 stagger)
// KB Land가 과거 기준일 값을 사후 보정할 수 있어, 매주는 최근 구간만 가볍게 갱신하고
// 월 1회(매월 1일 08:20 KST) 20년 전체를 다시 받아 오래된 구간의 보정도 반영한다.
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true)
public class KbLandPriceIndexScheduler {

    private static final int RECENT_YEARS = 2;
    private static final int FULL_REFRESH_YEARS = 20;

    private final FetchHousingPriceIndexUseCase fetchHousingPriceIndexUseCase;
    private final SchedulerJobRunner jobRunner;
    private final SchedulerLockService schedulerLockService;

    @Scheduled(cron = "0 10 8 * * SAT", zone = TimeZones.KST_ID) // 매주 토요일 08:10 KST
    public void run() throws InterruptedException {
        trigger("kbland-price-index", RECENT_YEARS, "KB Land 주간 아파트 매매가격지수 수집 스케쥴러");
    }

    @Scheduled(cron = "0 20 8 1 * *", zone = TimeZones.KST_ID) // 매월 1일 08:20 KST
    public void runFullRefresh() throws InterruptedException {
        trigger("kbland-price-index-full", FULL_REFRESH_YEARS, "KB Land 주간 아파트 매매가격지수 월간 풀 리프레시 스케쥴러");
    }

    // 수동 트리거 — 크론 대기 없이 즉시 실행. run()과 락 이름을 공유해 크론과 동시 실행되지 않음
    public void runNow() throws InterruptedException {
        trigger("kbland-price-index", RECENT_YEARS, "KB Land 주간 아파트 매매가격지수 수집 스케쥴러 수동");
    }

    // 수동 풀 리프레시 트리거 — KB Land 과거 값 보정을 다음 달 1일까지 기다리지 않고 즉시 반영해야 할 때 사용.
    // runFullRefresh()와 락 이름을 공유해 크론과 동시 실행되지 않음
    public void runFullRefreshNow() throws InterruptedException {
        trigger("kbland-price-index-full", FULL_REFRESH_YEARS, "KB Land 주간 아파트 매매가격지수 월간 풀 리프레시 스케쥴러 수동");
    }

    // 락 획득 + 지정 연도 범위 수집 실행 — 락 이름·연도·로그 라벨만 다른 4개 진입점의 공통 골격
    private void trigger(String lockName, int years, String label) throws InterruptedException {
        schedulerLockService.tryRun(lockName, Duration.ofMinutes(30),
                () -> jobRunner.run(label, () -> fetchHousingPriceIndexUseCase.fetchAndSave(years)));
    }
}
