package com.kista.adapter.in.schedule;

import com.kista.common.TimeZones;
import com.kista.application.port.output.MarketCalendarRefreshPort;
import com.kista.application.port.output.MarketHolidayStorePort;
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
    private final MarketHolidayStorePort marketHolidayStorePort;
    private final SchedulerJobRunner jobRunner;
    private final SchedulerLockService schedulerLockService;

    // 앱 기동 시 당해 연도 캘린더 데이터 없으면 3년치 자동 적재
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() throws InterruptedException {
        schedulerLockService.tryRun("market-calendar-startup", Duration.ofHours(1), () -> {
            int year = LocalDate.now(TimeZones.KST).getYear();
            if (allYearsPresentSafely(year, 3)) {
                // 적재할 데이터 없음 — jobRunner 알림 자체를 생략해 매 기동마다 텔레그램 오는 것 방지
                log.debug("캘린더 {}~{} 이미 적재됨 — 기동 시 초기 적재 스킵", year, year + 2);
                return;
            }
            // 기동 중단 방지 — jobRunner가 오류 처리 및 알림 담당
            jobRunner.run("기동 시 캘린더 초기 적재", () -> refreshYearsIfMissing(year, 3));
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

    // count년치 모두 이미 적재됐는지 확인 — 기동 알림 생략 여부 판단용
    // jobRunner 보호망 밖에서 실행되므로 DB 조회 실패 시 false로 fail-open해 jobRunner.run 경로(오류 알림 담당)로 넘긴다
    private boolean allYearsPresentSafely(int startYear, int count) {
        try {
            for (int i = 0; i < count; i++) {
                if (marketHolidayStorePort.countByYear(startYear + i) == 0) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("캘린더 적재 여부 사전 확인 실패 — 기동 초기 적재로 진행: {}", e.getMessage());
            return false;
        }
    }

    // 연도별 데이터가 이미 있으면 스킵 — 재기동마다 불필요한 외부 API 재조회 방지
    private void refreshYearsIfMissing(int startYear, int count) {
        for (int i = 0; i < count; i++) {
            int year = startYear + i;
            if (marketHolidayStorePort.countByYear(year) == 0) {
                marketCalendarRefreshPort.refreshCalendar(year);
            }
        }
    }
}
