package com.kista.trading.adapter.in.schedule;

import com.kista.sharedkernel.TimeZones;
import com.kista.trading.application.port.output.TradingBatchRunPort;
import com.kista.trading.application.port.output.TradingErrorReportPort;
import com.kista.trading.domain.model.TradingBatchJob;
import com.kista.trading.domain.model.TradingBatchPhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
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

    TradingBatchResumer resumer;

    private static final LocalDate WED = LocalDate.of(2026, 10, 7);

    @BeforeEach
    void setUp() {
        resumer = new TradingBatchResumer(batchRunPort, closeScheduler, openScheduler, errorReportPort);
    }

    private static ZonedDateTime kst(int month, int day, int hour, int minute) {
        return ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, TimeZones.KST);
    }

    private void phase(TradingBatchJob job, LocalDate date, TradingBatchPhase phase) {
        when(batchRunPort.findPhase(job, date)).thenReturn(Optional.ofNullable(phase));
    }

    @Test
    void close_noRow_beforeCutoff_resumesFullBatch() throws Exception {
        phase(TradingBatchJob.CLOSE, WED, null);

        resumer.resume(kst(10, 7, 4, 40));

        verify(closeScheduler).resume();
        verify(closeScheduler, never()).resumeReport();
    }

    @Test
    void close_planned_afterCutoff_alertsOnly() throws Exception {
        phase(TradingBatchJob.CLOSE, WED, TradingBatchPhase.PLANNED);

        resumer.resume(kst(10, 7, 4, 55));

        verify(closeScheduler, never()).resume();
        verify(errorReportPort).reportError(argThat(e -> e.getMessage().contains("재개 불가")));
    }

    @Test
    void close_placing_beforeCutoff_warnsThenResumes() throws Exception {
        phase(TradingBatchJob.CLOSE, WED, TradingBatchPhase.PLACING);

        resumer.resume(kst(10, 7, 4, 40));

        verify(errorReportPort).reportError(argThat(e -> e.getMessage().contains("이중 접수")));
        verify(closeScheduler).resume();
    }

    @Test
    void close_placed_anyTimeInWindow_resumesReport() throws Exception {
        phase(TradingBatchJob.CLOSE, WED, TradingBatchPhase.PLACED);

        resumer.resume(kst(10, 7, 10, 0));

        verify(closeScheduler).resumeReport();
        verify(closeScheduler, never()).resume();
    }

    @Test
    void close_done_nothing() {
        phase(TradingBatchJob.CLOSE, WED, TradingBatchPhase.DONE);

        resumer.resume(kst(10, 7, 4, 40));

        verifyNoInteractions(closeScheduler, openScheduler, errorReportPort);
    }

    @Test
    void monday_morning_noWindow() {
        resumer.resume(kst(10, 5, 5, 0)); // 월요일 — 마감 배치 없음, 개장 자정 이후 창(화~토)도 아님

        verifyNoInteractions(batchRunPort, closeScheduler, openScheduler);
    }

    @Test
    void open_mondayEvening_noRow_resumesWithTuesdayTradeDate() throws Exception {
        phase(TradingBatchJob.OPEN, LocalDate.of(2026, 10, 6), null);

        resumer.resume(kst(10, 5, 23, 0));

        verify(openScheduler).resume();
    }

    @Test
    void open_afterMidnight_tradeDateIsToday() throws Exception {
        phase(TradingBatchJob.OPEN, LocalDate.of(2026, 10, 6), null);

        resumer.resume(kst(10, 6, 0, 30)); // 화 00:30 — 거래일 화

        verify(openScheduler).resume();
    }

    @Test
    void open_done_nothing() throws Exception {
        phase(TradingBatchJob.OPEN, LocalDate.of(2026, 10, 6), TradingBatchPhase.DONE);

        resumer.resume(kst(10, 5, 23, 0));

        verify(openScheduler, never()).resume();
    }

    @Test
    void saturdayEvening_sundayNight_noWindow() {
        resumer.resume(kst(10, 10, 23, 0)); // 토 23:00
        resumer.resume(kst(10, 11, 3, 0));  // 일 03:00

        verifyNoInteractions(batchRunPort, closeScheduler, openScheduler);
    }

    @Test
    void close_resumeThrows_reportedToAdmin() throws Exception {
        phase(TradingBatchJob.CLOSE, WED, null);
        doThrow(new IllegalStateException("boom")).when(closeScheduler).resume();

        resumer.resume(kst(10, 7, 4, 40));

        verify(errorReportPort).reportError(argThat(e -> e.getMessage().contains("boom")));
    }
}
