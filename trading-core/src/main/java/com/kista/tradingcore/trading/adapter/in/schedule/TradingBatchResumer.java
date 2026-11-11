package com.kista.tradingcore.trading.adapter.in.schedule;

import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.trading.application.port.output.TradingBatchRunPort;
import com.kista.tradingcore.trading.application.port.output.TradingErrorReportPort;
import com.kista.tradingcore.trading.domain.model.TradingBatchJob;
import com.kista.tradingcore.trading.domain.model.TradingBatchPhase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

// 기동 시 오늘 거래일의 미완료 매매 배치를 체크포인트 단계부터 재개 — 단일 인스턴스·비겹침 배포 전제(잔여 락 인수)
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true) // 스케쥴러 비활성이면 재개도 비활성
@Profile("!local") // local은 상시 기동이 아니라 기동마다 "재개 불가"를 운영과 같은 관리자 봇에 보낸다 — 재개 비활성
public class TradingBatchResumer {

    private static final LocalTime OPEN_CRON = TradingOpenScheduler.CRON_TIME;  // TradingOpenScheduler cron 시각
    private static final LocalTime OPEN_RESUME_END = LocalTime.of(4, 30); // 기존 개장 유스케이스의 거래일 경계

    private final TradingBatchRunPort batchRunPort;
    private final TradingCloseScheduler closeScheduler;
    private final TradingOpenScheduler openScheduler;
    private final TradingErrorReportPort errorReportPort; // 재개 시작·불가·경고 관리자 알림
    private final Clock clock; // 현재 시각 기준 — 테스트에서 Clock.fixed 주입
    private final MarketCalendarPort marketCalendarPort;

    // 기동을 막지 않도록 별도 VT에서 실행 — 재개 배치는 대기를 포함해 길게 돈다
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE) // 캘린더 기동 적재(MarketCalendarRefreshScheduler.onStartup) 뒤 — 첫 배포 시 빈 세션 테이블로 예약이 빠지지 않게
    void onReady() {
        Thread.ofVirtual().name("trading-batch-resumer").start(() -> resume(ZonedDateTime.now(clock.withZone(TimeZones.KST))));
    }

    // 익일 예약을 먼저 등록해 당일 리포트 복구의 대기가 예약을 지연시키지 않게 한다
    // 단계마다 실패를 격리한다 — 한 거래일 복구 실패가 다른 거래일 예약·개장 재개를 막지 않게
    void resume(ZonedDateTime nowKst) {
        ZonedDateTime now = nowKst.withZoneSameInstant(TimeZones.KST);
        LocalDate today = now.toLocalDate();
        int day = now.getDayOfWeek().getValue();
        if (day <= DayOfWeek.FRIDAY.getValue() && !now.toLocalTime().isBefore(OPEN_CRON)
                && !step(() -> recoverClose(today.plusDays(1), now.toInstant()))) return;
        if (day >= DayOfWeek.TUESDAY.getValue() && day <= DayOfWeek.SATURDAY.getValue()
                && !step(() -> recoverClose(today, now.toInstant()))) return;
        step(() -> resumeOpen(ZonedDateTime.now(clock.withZone(TimeZones.KST))));
    }

    // 인터럽트면 false(이후 단계 중단), 그 밖의 실패는 관리자 알림 후 다음 단계 계속
    private boolean step(ResumeStep step) {
        try {
            step.run();
            return true;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt(); // 재개 도중 다시 종료 — 다음 기동이 이어받음
            return false;
        } catch (Exception e) {
            log.error("매매 배치 재개 실패: {}", e.getMessage(), e);
            try {
                errorReportPort.reportError(e);
            } catch (Exception notificationFailure) {
                log.error("관리자 알림 실패", notificationFailure); // 알림 실패가 다음 단계를 막지 않게
            }
            return true;
        }
    }

    @FunctionalInterface
    private interface ResumeStep {
        void run() throws InterruptedException;
    }

    private void recoverClose(LocalDate tradeDate, Instant now) throws InterruptedException {
        var session = marketCalendarPort.findSession(tradeDate);
        if (session.isEmpty()) return;
        TradingBatchPhase phase = batchRunPort.findPhase(TradingBatchJob.CLOSE, tradeDate).orElse(null);
        if (phase == TradingBatchPhase.DONE) return;
        if (phase == TradingBatchPhase.PLACED) {
            if (!tradeDate.equals(now.atZone(TimeZones.KST).toLocalDate())) {
                alert("[재개 불가] trading-close 리포트 거래일 불일치 — " + tradeDate);
                return;
            }
            alert("[재개] trading-close 접수 완료 이후 중단 — 리포트 재개 (거래일 " + tradeDate + ")");
            closeScheduler.resumeReport();
            return;
        }
        Instant orderAt = session.get().closeAt().minus(TradingCloseScheduler.ORDER_MARGIN);
        if (now.isBefore(orderAt)) {
            closeScheduler.schedule(tradeDate);
            return;
        }
        if (phase == TradingBatchPhase.PLACING) {
            alert("[재개 경고] trading-close 접수 도중 중단 — 이중 접수 여부 확인 필요 (거래일 " + tradeDate + ") — SUBMITTING 잔류 주문은 재접수되지 않음");
        }
        if (!now.isBefore(session.get().closeAt().minus(TradingCloseScheduler.PLACEMENT_MARGIN))
                || !tradeDate.equals(now.atZone(TimeZones.KST).toLocalDate())) {
            alert("[재개 불가] trading-close 접수 마감 경과 (phase=" + phase + ", 거래일 " + tradeDate + ") — 마감 매매 미접수, 수동 확인 필요");
            return;
        }
        alert("[재개] trading-close 마감 배치 재실행 (phase=" + phase + ", 거래일 " + tradeDate + ")");
        closeScheduler.resume();
    }

    // 개장 유스케이스가 현재 시각으로 거래일을 계산하므로 기존 04:30 경계를 넘겨 재개하지 않는다
    private void resumeOpen(ZonedDateTime nowKst) throws InterruptedException {
        int day = nowKst.getDayOfWeek().getValue();
        LocalTime time = nowKst.toLocalTime();
        boolean evening = day <= DayOfWeek.FRIDAY.getValue() && !time.isBefore(OPEN_CRON);
        boolean afterMidnight = day >= DayOfWeek.TUESDAY.getValue() && day <= DayOfWeek.SATURDAY.getValue()
                && time.isBefore(OPEN_RESUME_END);
        if (!evening && !afterMidnight) return;

        LocalDate tradeDate = evening ? nowKst.toLocalDate().plusDays(1) : nowKst.toLocalDate();
        var session = marketCalendarPort.findSession(tradeDate);
        if (session.isEmpty() || !nowKst.toInstant().isBefore(session.get().closeAt().minus(TradingCloseScheduler.ORDER_MARGIN))) return;
        TradingBatchPhase phase = batchRunPort.findPhase(TradingBatchJob.OPEN, tradeDate).orElse(null);
        if (phase == TradingBatchPhase.DONE) return;
        if (phase == TradingBatchPhase.PLACING) {
            alert("[재개 경고] trading-open 접수 도중 중단 — 이중 접수 여부 확인 필요 (거래일 " + tradeDate + ") — SUBMITTING 잔류 주문은 재접수되지 않음");
        }
        alert("[재개] trading-open 개장 배치 재실행 (phase=" + phase + ", 거래일 " + tradeDate + ")");
        openScheduler.resume();
    }

    private void alert(String message) {
        log.warn(message);
        errorReportPort.reportError(new IllegalStateException(message));
    }
}
