package com.kista.adapter.in.schedule;

import com.kista.common.TimeZones;
import com.kista.domain.port.out.MarketCalendarRefreshPort;
import com.kista.domain.port.out.NotifyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketCalendarRefreshScheduler {

    private final MarketCalendarRefreshPort marketCalendarRefreshPort;
    private final NotifyPort notifyPort; // 스케쥴러 시작/종료 알림
    private final SchedulerLockService schedulerLockService;

    // 앱 기동 시 당해 연도 캘린더 데이터 없으면 3년치 자동 적재
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() throws InterruptedException {
        schedulerLockService.tryRun("market-calendar-startup", Duration.ofHours(1), () -> {
            int year = LocalDate.now(TimeZones.KST).getYear();
            try {
                refreshYears(year, 3);
            } catch (Exception e) {
                // 기동 중단 방지 — 오류 로그만 남기고 계속 (isMarketOpen이 안전 폴백 처리)
                log.error("기동 시 시장 캘린더 갱신 실패: {}", e.getMessage(), e);
                notifyPort.notifyError(e);
            }
        });
    }

    // 매년 1월 1일 00:00 KST — 당해 연도 포함 향후 3년치 적재
    @Scheduled(cron = "0 0 0 1 1 *", zone = TimeZones.KST_ID)
    public void refreshForNewYear() throws InterruptedException {
        schedulerLockService.tryRun("market-calendar-yearly", Duration.ofHours(1), this::refreshForNewYearLocked);
    }

    private void refreshForNewYearLocked() {
        notifyPort.notifyInfo("연간 캘린더 갱신 스케쥴러 시작");
        int year = LocalDate.now(TimeZones.KST).getYear();
        log.info("연간 시장 캘린더 갱신 스케쥴러 실행: {}~{}년", year, year + 2);
        try {
            refreshYears(year, 3);
        } catch (Exception e) {
            log.error("연간 시장 캘린더 갱신 실패: {}", e.getMessage(), e);
            notifyPort.notifyError(e);
        }
        notifyPort.notifyInfo("연간 캘린더 갱신 스케쥴러 완료");
    }

    // 매월 1일 01:00 KST — 해당 월 데이터만 최신화
    @Scheduled(cron = "0 0 1 1 * *", zone = TimeZones.KST_ID)
    public void refreshCurrentMonth() throws InterruptedException {
        schedulerLockService.tryRun("market-calendar-monthly", Duration.ofMinutes(30), this::refreshCurrentMonthLocked);
    }

    private void refreshCurrentMonthLocked() {
        notifyPort.notifyInfo("월간 캘린더 갱신 스케쥴러 시작");
        LocalDate today = LocalDate.now(TimeZones.KST);
        log.info("월간 시장 캘린더 갱신 스케쥴러 실행: {}년 {}월", today.getYear(), today.getMonthValue());
        try {
            marketCalendarRefreshPort.refreshMonth(today.getYear(), today.getMonthValue());
        } catch (Exception e) {
            log.error("월간 시장 캘린더 갱신 실패: {}", e.getMessage(), e);
            notifyPort.notifyError(e);
        }
        notifyPort.notifyInfo("월간 캘린더 갱신 스케쥴러 완료");
    }

    private void refreshYears(int startYear, int count) {
        for (int i = 0; i < count; i++) {
            marketCalendarRefreshPort.refreshCalendar(startYear + i);
        }
    }
}
