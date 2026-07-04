package com.kista.adapter.in.schedule;

import com.kista.common.TimeZones;
import com.kista.domain.port.out.MarketCalendarRefreshPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true) // local에서 끄면 운영 DB·텔레그램과 중복 알림 발생 방지
public class MarketCalendarRefreshScheduler {

    private final MarketCalendarRefreshPort marketCalendarRefreshPort;
    private final SchedulerJobRunner jobRunner;
    private final SchedulerLockService schedulerLockService;

    // 앱 기동 시 당해 연도 캘린더 데이터 없으면 3년치 자동 적재
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() throws InterruptedException {
        schedulerLockService.tryRun("market-calendar-startup", Duration.ofHours(1), () -> {
            int year = LocalDate.now(TimeZones.KST).getYear();
            // 기동 중단 방지 — jobRunner가 오류 처리 및 알림 담당
            jobRunner.run("기동 시 캘린더 초기 적재", () -> refreshYears(year, 3));
        });
    }

    // 매년 1월 1일 00:00 KST — 당해 연도 포함 향후 3년치 적재
    @Scheduled(cron = "0 0 0 1 1 *", zone = TimeZones.KST_ID)
    public void refreshForNewYear() throws InterruptedException {
        schedulerLockService.tryRun("market-calendar-yearly", Duration.ofHours(1), this::refreshForNewYearLocked);
    }

    private void refreshForNewYearLocked() {
        int year = LocalDate.now(TimeZones.KST).getYear();
        jobRunner.run("연간 캘린더 갱신 스케쥴러", () -> refreshYears(year, 3));
    }

    // 매월 1일 01:00 KST — 해당 월 데이터만 최신화
    @Scheduled(cron = "0 0 1 1 * *", zone = TimeZones.KST_ID)
    public void refreshCurrentMonth() throws InterruptedException {
        schedulerLockService.tryRun("market-calendar-monthly", Duration.ofMinutes(30), this::refreshCurrentMonthLocked);
    }

    private void refreshCurrentMonthLocked() {
        LocalDate today = LocalDate.now(TimeZones.KST);
        jobRunner.run("월간 캘린더 갱신 스케쥴러",
            () -> marketCalendarRefreshPort.refreshMonth(today.getYear(), today.getMonthValue()));
    }

    private void refreshYears(int startYear, int count) {
        for (int i = 0; i < count; i++) {
            marketCalendarRefreshPort.refreshCalendar(startYear + i);
        }
    }
}
