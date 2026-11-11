package com.kista.tradingcore.trading.adapter.in.schedule;

import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.marketcalendar.domain.model.MarketSessionTimes;
import com.kista.tradingcore.trading.application.port.output.TradingBatchRunPort;
import com.kista.tradingcore.trading.application.port.output.TradingErrorReportPort;
import com.kista.tradingcore.trading.domain.model.TradingBatchJob;
import com.kista.tradingcore.trading.domain.model.TradingBatchPhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.Clock;
import java.time.Instant;
import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

// 재개 판정 표(spec 6장) — 2026-10은 미국 DST: 장마감 05:00 KST, 마감 접수 마감 04:50. 10-05가 월요일
@ExtendWith(MockitoExtension.class)
class TradingBatchResumerTest {

    @Mock TradingBatchRunPort batchRunPort;
    @Mock TradingCloseScheduler closeScheduler;
    @Mock TradingOpenScheduler openScheduler;
    @Mock TradingErrorReportPort errorReportPort;
    @Mock MarketCalendarPort calendar;
    @Mock Clock clock;

    Instant now;

    TradingBatchResumer resumer;

    private static final LocalDate WED = LocalDate.of(2026, 10, 7);

    @BeforeEach
    void setUp() {
        resumer = new TradingBatchResumer(batchRunPort, closeScheduler, openScheduler, errorReportPort, clock, calendar);
        lenient().when(clock.withZone(TimeZones.KST)).thenReturn(clock);
        lenient().when(clock.getZone()).thenReturn(TimeZones.KST);
        lenient().when(clock.instant()).thenAnswer(_ -> now);
        lenient().when(calendar.findSession(any())).thenAnswer(invocation -> {
            LocalDate date = invocation.getArgument(0);
            if (date.getDayOfWeek() == DayOfWeek.MONDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) return Optional.empty();
            return Optional.of(new MarketSessionTimes(date.minusDays(1).atTime(22, 30).atZone(TimeZones.KST).toInstant(),
                    date.atTime(5, 0).atZone(TimeZones.KST).toInstant()));
        });
    }

    private static ZonedDateTime kst(int month, int day, int hour, int minute) {
        return ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, TimeZones.KST);
    }

    private void phase(TradingBatchJob job, LocalDate date, TradingBatchPhase phase) {
        // 개장 재개 전에 마감 예약 복구가 다른 job·날짜로 조회하므로 lenient
        lenient().when(batchRunPort.findPhase(job, date)).thenReturn(Optional.ofNullable(phase));
    }

    private void resume(ZonedDateTime time) {
        now = time.toInstant();
        resumer.resume(time);
    }

    @Test
    void close_noRow_beforeCutoff_resumesFullBatch() throws Exception {
        phase(TradingBatchJob.CLOSE, WED, null);

        resume(kst(10, 7, 4, 40));

        verify(closeScheduler).resume();
        verify(closeScheduler, never()).resumeReport();
    }

    @Test
    void close_planned_afterCutoff_alertsOnly() throws Exception {
        phase(TradingBatchJob.CLOSE, WED, TradingBatchPhase.PLANNED);

        resume(kst(10, 7, 4, 55));

        verify(closeScheduler, never()).resume();
        verify(errorReportPort).reportError(argThat(e -> e.getMessage().contains("재개 불가")));
    }

    @Test
    void close_placing_beforeCutoff_warnsThenResumes() throws Exception {
        phase(TradingBatchJob.CLOSE, WED, TradingBatchPhase.PLACING);

        resume(kst(10, 7, 4, 40));

        verify(errorReportPort).reportError(argThat(e -> e.getMessage().contains("이중 접수")));
        verify(closeScheduler).resume();
    }

    @Test
    void close_placed_anyTimeInWindow_resumesReport() throws Exception {
        phase(TradingBatchJob.CLOSE, WED, TradingBatchPhase.PLACED);

        resume(kst(10, 7, 10, 0));

        verify(closeScheduler).resumeReport();
        verify(closeScheduler, never()).resume();
    }

    @Test
    void close_done_nothing() {
        phase(TradingBatchJob.CLOSE, WED, TradingBatchPhase.DONE);

        resume(kst(10, 7, 4, 40));

        verifyNoInteractions(closeScheduler, openScheduler, errorReportPort);
    }

    @Test
    void monday_morning_noWindow() {
        resume(kst(10, 5, 5, 0)); // 월요일 — 마감 배치 없음, 개장 자정 이후 창(화~토)도 아님

        verifyNoInteractions(batchRunPort, closeScheduler, openScheduler);
    }

    @Test
    void open_mondayEvening_noRow_resumesWithTuesdayTradeDate() throws Exception {
        phase(TradingBatchJob.OPEN, LocalDate.of(2026, 10, 6), null);

        resume(kst(10, 5, 23, 0));

        verify(openScheduler).resume();
    }

    @Test
    void open_mondayAfter2200_resumesWithTuesdayTradeDate() throws Exception {
        phase(TradingBatchJob.OPEN, LocalDate.of(2026, 10, 6), null);

        resume(kst(10, 5, 22, 10)); // 월 22:10 — 개장 배치 cron(22:00) 이후

        verify(openScheduler).resume();
    }

    @Test
    void open_mondayBefore2200_noWindow() {
        resume(kst(10, 5, 21, 59));

        verifyNoInteractions(openScheduler);
    }

    @Test
    void open_afterMidnight_tradeDateIsToday() throws Exception {
        phase(TradingBatchJob.OPEN, LocalDate.of(2026, 10, 6), null);

        resume(kst(10, 6, 0, 30)); // 화 00:30 — 거래일 화

        verify(openScheduler).resume();
    }

    @Test
    void open_done_nothing() throws Exception {
        phase(TradingBatchJob.OPEN, LocalDate.of(2026, 10, 6), TradingBatchPhase.DONE);

        resume(kst(10, 5, 23, 0));

        verify(openScheduler, never()).resume();
    }

    @Test
    void saturdayEvening_sundayNight_noWindow() {
        phase(TradingBatchJob.CLOSE, LocalDate.of(2026, 10, 10), TradingBatchPhase.DONE);
        resume(kst(10, 10, 23, 0));
        resume(kst(10, 11, 3, 0));

        verifyNoInteractions(closeScheduler, openScheduler);
    }

    @Test
    void close_resumeThrows_reportedToAdmin() throws Exception {
        phase(TradingBatchJob.CLOSE, WED, null);
        doThrow(new IllegalStateException("boom")).when(closeScheduler).resume();

        resume(kst(10, 7, 4, 40));

        verify(errorReportPort).reportError(argThat(e -> e.getMessage().contains("boom")));
    }

    @Test
    void close_started_beforeCutoff_resumesFullBatch() throws Exception {
        phase(TradingBatchJob.CLOSE, WED, TradingBatchPhase.STARTED); // 수동 DONE을 덮어쓴 cron 시작 기록

        resume(kst(10, 7, 4, 40));

        verify(closeScheduler).resume();
    }

    @Test
    void open_started_resumes() throws Exception {
        phase(TradingBatchJob.OPEN, LocalDate.of(2026, 10, 6), TradingBatchPhase.STARTED);

        resume(kst(10, 5, 23, 0));

        verify(openScheduler).resume();
    }

    @Test
    void beforeOrderTime_reservesCloseAndResumesOpen() throws Exception {
        resume(kst(10, 7, 0, 30));

        verify(closeScheduler).schedule(WED);
        verify(closeScheduler, never()).resume();
        verify(openScheduler).resume();
    }

    @Test
    void earlyClose_afterOrderTimePrioritizesCloseAndSkipsOpen() throws Exception {
        ZonedDateTime time = kst(10, 7, 1, 35);
        when(calendar.findSession(WED)).thenReturn(Optional.of(new MarketSessionTimes(
                kst(10, 6, 22, 30).toInstant(), kst(10, 7, 2, 0).toInstant())));

        resume(time);

        verify(closeScheduler).resume();
        verify(openScheduler, never()).resume();
    }

    @Test
    void holiday_skipsReservationAndRecovery() {
        when(calendar.findSession(WED)).thenReturn(Optional.empty());

        resume(kst(10, 7, 0, 30));

        verifyNoInteractions(closeScheduler, openScheduler, batchRunPort, errorReportPort);
    }

    @Test
    void missingSession_reportsErrorWithoutRunning() {
        when(calendar.findSession(WED)).thenThrow(new IllegalStateException("not loaded"));

        resume(kst(10, 7, 0, 30));

        verify(errorReportPort, atLeastOnce()).reportError(argThat(e -> e.getMessage().contains("not loaded"))); // 단계별 격리 — 마감·개장 각각 보고
        verifyNoInteractions(closeScheduler, openScheduler);
    }
}
