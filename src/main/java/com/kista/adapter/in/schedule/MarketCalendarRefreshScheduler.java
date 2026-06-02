package com.kista.adapter.in.schedule;

import com.kista.domain.port.out.MarketCalendarRefreshPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketCalendarRefreshScheduler {

    private final MarketCalendarRefreshPort marketCalendarRefreshPort;

    // 앱 기동 시 당해 연도 캘린더 데이터 없으면 자동 갱신
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        int year = LocalDate.now().getYear();
        try {
            marketCalendarRefreshPort.refreshCalendar(year);
        } catch (Exception e) {
            // 기동 중단 방지 — 오류 로그만 남기고 계속 (isMarketOpen이 안전 폴백 처리)
            log.error("기동 시 {}년 시장 캘린더 갱신 실패: {}", year, e.getMessage(), e);
        }
    }

    // 매월 1일 01:00 KST — 당해 연도 캘린더 갱신, 12월엔 다음 연도도 선제 적재
    @Scheduled(cron = "0 0 1 1 * *", zone = "Asia/Seoul")
    public void refreshCurrentYear() {
        int year = LocalDate.now().getYear();
        log.info("월간 시장 캘린더 갱신 스케줄 실행: {}년", year);
        try {
            marketCalendarRefreshPort.refreshCalendar(year);
            // 12월에는 내년 데이터 선제 적재
            if (LocalDate.now().getMonthValue() == 12) {
                log.info("12월 선제 갱신: {}년", year + 1);
                marketCalendarRefreshPort.refreshCalendar(year + 1);
            }
        } catch (Exception e) {
            log.error("시장 캘린더 갱신 실패: {}", e.getMessage(), e);
        }
    }
}
