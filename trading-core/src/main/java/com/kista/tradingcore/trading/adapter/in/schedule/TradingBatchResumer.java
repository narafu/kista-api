package com.kista.tradingcore.trading.adapter.in.schedule;

import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.trading.application.port.output.TradingBatchRunPort;
import com.kista.tradingcore.trading.application.port.output.TradingErrorReportPort;
import com.kista.tradingcore.trading.domain.model.DstInfo;
import com.kista.tradingcore.trading.domain.model.TradingBatchJob;
import com.kista.tradingcore.trading.domain.model.TradingBatchPhase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

// 기동 시 오늘 거래일의 미완료 매매 배치를 체크포인트 단계부터 재개 — 단일 인스턴스·비겹침 배포 전제(잔여 락 인수)
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true) // 스케쥴러 비활성(local)이면 재개도 비활성
public class TradingBatchResumer {

    private static final LocalTime CLOSE_CRON = TradingCloseScheduler.CRON_TIME;  // TradingCloseScheduler cron 시각
    private static final LocalTime OPEN_CRON = LocalTime.of(22, 0);  // TradingOpenScheduler cron 시각

    private final TradingBatchRunPort batchRunPort;
    private final TradingCloseScheduler closeScheduler;
    private final TradingOpenScheduler openScheduler;
    private final TradingErrorReportPort errorReportPort; // 재개 시작·불가·경고 관리자 알림

    // 기동을 막지 않도록 별도 VT에서 실행 — 재개 배치는 대기를 포함해 길게 돈다
    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        Thread.ofVirtual().name("trading-batch-resumer").start(() -> resume(ZonedDateTime.now(TimeZones.KST)));
    }

    // 마감·개장 판정 창은 겹치지 않아 최대 하나만 실행된다
    void resume(ZonedDateTime nowKst) {
        try {
            resumeClose(nowKst);
            resumeOpen(nowKst);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt(); // 재개 도중 다시 종료 — 다음 기동이 이어받음
        } catch (Exception e) {
            log.error("매매 배치 재개 실패: {}", e.getMessage(), e);
            errorReportPort.reportError(e);
        }
    }

    // 화~토 04:30~22:00 — 당일 마감 배치
    private void resumeClose(ZonedDateTime nowKst) throws InterruptedException {
        int day = nowKst.getDayOfWeek().getValue();
        LocalTime time = nowKst.toLocalTime();
        boolean inWindow = day >= DayOfWeek.TUESDAY.getValue() && day <= DayOfWeek.SATURDAY.getValue()
                && !time.isBefore(CLOSE_CRON) && time.isBefore(OPEN_CRON);
        if (!inWindow) return;

        LocalDate tradeDate = nowKst.toLocalDate();
        TradingBatchPhase phase = batchRunPort.findPhase(TradingBatchJob.CLOSE, tradeDate).orElse(null);
        if (phase == TradingBatchPhase.DONE) return;
        if (phase == TradingBatchPhase.PLACED) {
            alert("[재개] trading-close 접수 완료 이후 중단 — 리포트 재개 (거래일 " + tradeDate + ")");
            closeScheduler.resumeReport();
            return;
        }
        if (phase == TradingBatchPhase.PLACING) {
            alert("[재개 경고] trading-close 접수 도중 중단 — 이중 접수 여부 확인 필요 (거래일 " + tradeDate + ")");
        }
        if (!TradingCloseScheduler.beforePlacementCutoff(nowKst)) {
            alert("[재개 불가] trading-close 접수 마감 경과 (phase=" + phase + ", 거래일 " + tradeDate + ") — 마감 매매 미접수, 수동 확인 필요");
            return;
        }
        alert("[재개] trading-close 마감 배치 재실행 (phase=" + phase + ", 거래일 " + tradeDate + ")");
        closeScheduler.resume();
    }

    // 월~금 22:00~24:00(거래일 익일) 또는 화~토 00:00~04:30(거래일 당일) — DstInfo.nextTradeDate()와 같은 04:30 경계
    private void resumeOpen(ZonedDateTime nowKst) throws InterruptedException {
        int day = nowKst.getDayOfWeek().getValue();
        LocalTime time = nowKst.toLocalTime();
        boolean evening = day <= DayOfWeek.FRIDAY.getValue() && !time.isBefore(OPEN_CRON);
        boolean afterMidnight = day >= DayOfWeek.TUESDAY.getValue() && day <= DayOfWeek.SATURDAY.getValue()
                && time.isBefore(CLOSE_CRON);
        if (!evening && !afterMidnight) return;

        LocalDate tradeDate = DstInfo.nextTradeDate(nowKst); // 저녁=익일, 자정 이후=당일
        TradingBatchPhase phase = batchRunPort.findPhase(TradingBatchJob.OPEN, tradeDate).orElse(null);
        if (phase == TradingBatchPhase.DONE) return;
        if (phase == TradingBatchPhase.PLACING) {
            alert("[재개 경고] trading-open 접수 도중 중단 — 이중 접수 여부 확인 필요 (거래일 " + tradeDate + ")");
        }
        alert("[재개] trading-open 개장 배치 재실행 (phase=" + phase + ", 거래일 " + tradeDate + ")");
        openScheduler.resume();
    }

    private void alert(String message) {
        log.warn(message);
        errorReportPort.reportError(new IllegalStateException(message));
    }
}
