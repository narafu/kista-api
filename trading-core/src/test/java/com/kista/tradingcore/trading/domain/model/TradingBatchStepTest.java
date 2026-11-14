package com.kista.tradingcore.trading.domain.model;

import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.marketcalendar.domain.model.MarketSessionTimes;
import com.kista.tradingcore.trading.domain.model.TradingBatchStep.Decision;
import com.kista.tradingcore.trading.domain.model.TradingBatchStep.Kind;
import com.kista.tradingcore.trading.domain.model.TradingBatchStep.Trigger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static com.kista.tradingcore.trading.domain.model.TradingBatchPhase.DONE;
import static com.kista.tradingcore.trading.domain.model.TradingBatchPhase.PLACED;
import static com.kista.tradingcore.trading.domain.model.TradingBatchPhase.PLACING;
import static com.kista.tradingcore.trading.domain.model.TradingBatchPhase.PLANNED;
import static com.kista.tradingcore.trading.domain.model.TradingBatchPhase.STARTED;
import static com.kista.tradingcore.trading.domain.model.TradingBatchStep.Kind.CLOSE_PLACE;
import static com.kista.tradingcore.trading.domain.model.TradingBatchStep.Kind.CLOSE_REPORT;
import static com.kista.tradingcore.trading.domain.model.TradingBatchStep.Kind.OPEN_PLACE;
import static com.kista.tradingcore.trading.domain.model.TradingBatchStep.Trigger.AUTOMATIC;
import static com.kista.tradingcore.trading.domain.model.TradingBatchStep.Trigger.MANUAL;
import static org.assertj.core.api.Assertions.assertThat;

// 판정표의 행 = 기존 스케줄러 테스트의 시각 시나리오. source는 옮겨 온 테스트 이름이고 "경계"는 이 표에서 새로 고정한 창 경계다
class TradingBatchStepTest {

    private static final LocalDate MON = LocalDate.of(2026, 10, 5);
    private static final LocalDate TUE = LocalDate.of(2026, 10, 6);
    private static final LocalDate WED = LocalDate.of(2026, 10, 7);
    private static final LocalDate DEC = LocalDate.of(2026, 12, 2); // 비DST 수요일

    private record Row(String source, Kind kind, LocalDate tradeDate, Optional<MarketSessionTimes> session,
                       TradingBatchPhase phase, Instant now, Trigger trigger, Consumer<Decision> expected) {
        @Override
        public String toString() {
            return source + " [" + kind + " " + tradeDate + " phase=" + phase + " " + trigger + "]";
        }
    }

    static List<Row> rows() {
        return List.of(
                // TradingBatchResumerTest
                row("close_noRow_beforeCutoff_resumesFullBatch", CLOSE_PLACE, WED, dst(WED), null, kst(10, 7, 4, 40), AUTOMATIC, run(false)),
                row("close_planned_afterCutoff_alertsOnly", CLOSE_PLACE, WED, dst(WED), PLANNED, kst(10, 7, 4, 55), AUTOMATIC, missed("[재개 불가]", false)),
                row("close_placing_beforeCutoff_warnsThenResumes", CLOSE_PLACE, WED, dst(WED), PLACING, kst(10, 7, 4, 40), AUTOMATIC, run(true)),
                row("close_placed_anyTimeInWindow_resumesReport", CLOSE_REPORT, WED, dst(WED), PLACED, kst(10, 7, 10, 0), AUTOMATIC, run(false)),
                row("close_placed_anyTimeInWindow_resumesReport(접수는 건너뜀)", CLOSE_PLACE, WED, dst(WED), PLACED, kst(10, 7, 10, 0), AUTOMATIC, skip()),
                row("close_done_nothing", CLOSE_PLACE, WED, dst(WED), DONE, kst(10, 7, 4, 40), AUTOMATIC, skip()),
                row("close_done_nothing(리포트)", CLOSE_REPORT, WED, dst(WED), DONE, kst(10, 7, 5, 30), AUTOMATIC, skip()),
                row("monday_morning_noWindow", CLOSE_PLACE, MON, holiday(), null, kst(10, 5, 5, 0), AUTOMATIC, skip()),
                row("open_mondayEvening_noRow_resumesWithTuesdayTradeDate", OPEN_PLACE, TUE, dst(TUE), null, kst(10, 5, 23, 0), AUTOMATIC, run(false)),
                // 지금은 22:00부터 스레드가 개장까지 잔다 — 표는 개장 시각 예약으로 답한다(접수 시각은 같다)
                row("open_mondayAfter2200_resumesWithTuesdayTradeDate", OPEN_PLACE, TUE, dst(TUE), null, kst(10, 5, 22, 10), AUTOMATIC, reserve(kst(10, 5, 22, 30))),
                // 지금 재개기는 22:00 전이면 아무것도 하지 않고 cron에 맡긴다 — 표는 개장 시각 예약으로 답한다
                row("open_mondayBefore2200_noWindow", OPEN_PLACE, TUE, dst(TUE), null, kst(10, 5, 21, 59), AUTOMATIC, reserve(kst(10, 5, 22, 30))),
                row("open_afterMidnight_tradeDateIsToday", OPEN_PLACE, TUE, dst(TUE), null, kst(10, 6, 0, 30), AUTOMATIC, run(false)),
                row("open_done_nothing", OPEN_PLACE, TUE, dst(TUE), DONE, kst(10, 5, 23, 0), AUTOMATIC, skip()),
                row("saturdayEvening_sundayNight_noWindow", OPEN_PLACE, LocalDate.of(2026, 10, 11), holiday(), null, kst(10, 10, 23, 0), AUTOMATIC, skip()),
                row("close_started_beforeCutoff_resumesFullBatch", CLOSE_PLACE, WED, dst(WED), STARTED, kst(10, 7, 4, 40), AUTOMATIC, run(false)),
                row("open_started_resumes", OPEN_PLACE, TUE, dst(TUE), STARTED, kst(10, 5, 23, 0), AUTOMATIC, run(false)),
                row("beforeOrderTime_reservesCloseAndResumesOpen(마감)", CLOSE_PLACE, WED, dst(WED), null, kst(10, 7, 0, 30), AUTOMATIC, reserve(kst(10, 7, 4, 30))),
                row("beforeOrderTime_reservesCloseAndResumesOpen(개장)", OPEN_PLACE, WED, dst(WED), null, kst(10, 7, 0, 30), AUTOMATIC, run(false)),
                row("earlyClose_afterOrderTimePrioritizesCloseAndSkipsOpen(마감)", CLOSE_PLACE, WED, early(WED), null, kst(10, 7, 1, 35), AUTOMATIC, run(false)),
                // 지금 재개기는 알림 없이 건너뛰고 개장 스케줄러의 canRun은 알린다 — 표는 Missed로 답하고 알릴지는 호출자가 정한다
                row("earlyClose_afterOrderTimePrioritizesCloseAndSkipsOpen(개장)", OPEN_PLACE, WED, early(WED), null, kst(10, 7, 1, 35), AUTOMATIC, missed("[개장 재개 누락]", false)),
                row("holiday_skipsReservationAndRecovery", CLOSE_PLACE, WED, holiday(), null, kst(10, 7, 0, 30), AUTOMATIC, skip()),

                // TradingCloseSchedulerTest
                row("runNow_insidePlacementWindow_runsImmediateBatch", CLOSE_PLACE, WED, dst(WED), null, kst(10, 7, 4, 40), MANUAL, run(false)),
                row("runNow_afterPlacementCutoff_rejectsWithAdminAlert", CLOSE_PLACE, WED, dst(WED), null, kst(10, 7, 4, 55), MANUAL, missed("[마감 접수 불가]", false)),
                row("runNow_windowBoundaries_dstAndNonDst(04:30)", CLOSE_PLACE, WED, dst(WED), null, kst(10, 7, 4, 30), MANUAL, run(false)),
                row("runNow_windowBoundaries_dstAndNonDst(비DST 05:49)", CLOSE_PLACE, DEC, nonDst(DEC), null, kst(12, 2, 5, 49), MANUAL, run(false)),
                row("runNow_windowBoundaries_dstAndNonDst(04:50)", CLOSE_PLACE, WED, dst(WED), null, kst(10, 7, 4, 50), MANUAL, missed("[마감 접수 불가]", false)),
                row("runNow_windowBoundaries_dstAndNonDst(비DST 05:50)", CLOSE_PLACE, DEC, nonDst(DEC), null, kst(12, 2, 5, 50), MANUAL, missed("[마감 접수 불가]", false)),
                row("runNow_eveningBeforeCron_rejects(23:00)", CLOSE_PLACE, WED, dst(WED), null, kst(10, 7, 23, 0), MANUAL, missed("[마감 접수 불가]", false)),
                row("runNow_eveningBeforeCron_rejects(03:20)", CLOSE_PLACE, WED, dst(WED), null, kst(10, 7, 3, 20), MANUAL, missed("[마감 접수 불가]", false)),
                row("resumeReport_takesOverCloseLock_runsReportOnly", CLOSE_REPORT, WED, dst(WED), PLACED, kst(10, 7, 5, 10), AUTOMATIC, run(false)),
                row("schedule_usesActualEarlyCloseAndSuppressesDuplicates", CLOSE_PLACE, WED, early(WED), null, kst(10, 6, 22, 0), AUTOMATIC, reserve(kst(10, 7, 1, 30))),
                row("reconcile_changedCloseReschedulesAndPastTodayIsSilent(변경 전)", CLOSE_PLACE, WED, dst(WED), null, kst(10, 6, 22, 0), AUTOMATIC, reserve(kst(10, 7, 4, 30))),
                row("reconcile_changedCloseReschedulesAndPastTodayIsSilent(변경 뒤)", CLOSE_PLACE, WED, early(WED), null, kst(10, 6, 22, 0), AUTOMATIC, reserve(kst(10, 7, 1, 30))),
                // 지난 예약을 조용히 넘기는 것은 호출자(매시 보정)의 몫이다
                row("reconcile_changedCloseReschedulesAndPastTodayIsSilent(오늘 경과)", CLOSE_PLACE, TUE, dst(TUE), null, kst(10, 6, 22, 0), AUTOMATIC, missed("[재개 불가]", false)),
                // 1분 조기 허용은 지금 예약 콜백(canPlace)에만 있다 — 재개기·예약 등록은 이 구간에서 예약한다. 표는 호출 경로를 구분하지 않고 Run이다
                row("callback_firesSlightlyEarly_stillRuns", CLOSE_PLACE, WED, dst(WED), null, kst(10, 7, 4, 29).plusSeconds(30), AUTOMATIC, run(false)),
                row("schedule_holidayAndDoneDoNotRegister(휴장)", CLOSE_PLACE, WED, holiday(), null, kst(10, 6, 22, 0), AUTOMATIC, skip()),
                row("schedule_holidayAndDoneDoNotRegister(완료)", CLOSE_PLACE, WED, dst(WED), DONE, kst(10, 6, 22, 0), AUTOMATIC, skip()),
                row("callback_afterCutoffAlertsWithoutExecuting", CLOSE_PLACE, WED, dst(WED), null, kst(10, 7, 4, 50), AUTOMATIC, missed("[재개 불가]", false)),
                row("callback_doneAfterReservationDoesNotRun", CLOSE_PLACE, WED, dst(WED), DONE, kst(10, 7, 4, 30), AUTOMATIC, skip()),

                // TradingOpenSchedulerTest
                row("run_holiday_pingsBothHeartbeatsWithoutBatch", OPEN_PLACE, WED, holiday(), null, kst(10, 6, 22, 0), AUTOMATIC, skip()),
                row("resume_takesOverOpenLock_runsOpenBatch", OPEN_PLACE, WED, dst(WED), null, kst(10, 6, 23, 0), AUTOMATIC, run(false)),
                // 수동 개장 실행은 지금 휴장·시각·체크포인트를 보지 않는다(TradingOpenScheduler.runNow)
                row("runNow(개장 수동 — 휴장)", OPEN_PLACE, WED, holiday(), null, kst(10, 6, 12, 0), MANUAL, run(false)),
                row("runNow(개장 수동 — 완료 뒤)", OPEN_PLACE, WED, dst(WED), DONE, kst(10, 7, 4, 45), MANUAL, run(false)),

                // 경계 — 개장 접수
                row("경계: 개장 시각 정각", OPEN_PLACE, WED, dst(WED), null, kst(10, 6, 22, 30), AUTOMATIC, run(false)),
                row("경계: 접수 도중 중단 뒤 개장 재개", OPEN_PLACE, WED, dst(WED), PLACING, kst(10, 6, 23, 0), AUTOMATIC, run(true)),
                row("경계: DST 마감 30분 전(04:30) 직전", OPEN_PLACE, WED, dst(WED), null, kst(10, 7, 4, 29), AUTOMATIC, run(false)),
                row("경계: DST 마감 30분 전(04:30)", OPEN_PLACE, WED, dst(WED), null, kst(10, 7, 4, 30), AUTOMATIC, missed("[개장 재개 누락]", false)),
                row("경계: 비DST는 마감 30분 전(05:30)보다 04:30 거래일 경계가 먼저", OPEN_PLACE, DEC, nonDst(DEC), null, kst(12, 2, 4, 30), AUTOMATIC, missed("[개장 재개 누락]", false)),
                row("경계: 조기 마감일 마감 30분 전(01:30) 직전", OPEN_PLACE, WED, early(WED), null, kst(10, 7, 1, 29), AUTOMATIC, run(false)),
                row("경계: 접수 도중 중단 + 창 경과", OPEN_PLACE, WED, dst(WED), PLACING, kst(10, 7, 4, 40), AUTOMATIC, missed("[개장 재개 누락]", true)),

                // 경계 — 마감 접수
                row("경계: 자동 창 시작 직전(마감 31분 전 − 1초)", CLOSE_PLACE, WED, dst(WED), null, kst(10, 7, 4, 29).minusSeconds(1), AUTOMATIC, reserve(kst(10, 7, 4, 30))),
                row("경계: 자동 창 시작(마감 31분 전)", CLOSE_PLACE, WED, dst(WED), null, kst(10, 7, 4, 29), AUTOMATIC, run(false)),
                row("경계: 자동 창 끝 직전(마감 10분 전 − 1초)", CLOSE_PLACE, WED, dst(WED), null, kst(10, 7, 4, 50).minusSeconds(1), AUTOMATIC, run(false)),
                row("경계: 접수 도중 중단 + 접수 마감 경과", CLOSE_PLACE, WED, dst(WED), PLACING, kst(10, 7, 4, 55), AUTOMATIC, missed("[재개 불가]", true)),
                row("경계: 접수 도중 중단 + 예약 시각 전", CLOSE_PLACE, WED, dst(WED), PLACING, kst(10, 7, 4, 0), AUTOMATIC, reserve(kst(10, 7, 4, 30))),
                row("경계: 수동 창 시작(마감 90분 전)", CLOSE_PLACE, WED, dst(WED), null, kst(10, 7, 3, 30), MANUAL, run(false)),
                row("경계: 수동 창 시작 직전", CLOSE_PLACE, WED, dst(WED), null, kst(10, 7, 3, 30).minusSeconds(1), MANUAL, missed("[마감 접수 불가]", false)),
                row("경계: 수동은 완료 뒤에도 다시 실행", CLOSE_PLACE, WED, dst(WED), DONE, kst(10, 7, 4, 40), MANUAL, run(false)),
                row("경계: 수동도 휴장이면 건너뜀", CLOSE_PLACE, WED, holiday(), null, kst(10, 7, 4, 40), MANUAL, skip()),
                row("경계: 조기 마감일 수동 창(00:30~01:50)", CLOSE_PLACE, WED, early(WED), null, kst(10, 7, 0, 30), MANUAL, run(false)),

                // 경계 — 마감 리포트
                row("경계: 리포트 시각 전(접수 완료)", CLOSE_REPORT, WED, dst(WED), PLACED, kst(10, 7, 4, 55), AUTOMATIC, reserve(kst(10, 7, 5, 10))),
                row("경계: 리포트 시각 전(접수 전)", CLOSE_REPORT, WED, dst(WED), null, kst(10, 7, 0, 30), AUTOMATIC, reserve(kst(10, 7, 5, 10))),
                row("경계: 접수가 끝나지 않은 날은 리포트 없음", CLOSE_REPORT, WED, dst(WED), PLANNED, kst(10, 7, 5, 30), AUTOMATIC, skip()),
                row("경계: 접수 도중 끊긴 날은 리포트 없음", CLOSE_REPORT, WED, dst(WED), PLACING, kst(10, 7, 5, 30), AUTOMATIC, skip()),
                row("경계: 거래일 KST 자정 직전", CLOSE_REPORT, WED, dst(WED), PLACED, kst(10, 7, 23, 59), AUTOMATIC, run(false)),
                row("경계: 거래일 경과(다음 날 00:00)", CLOSE_REPORT, WED, dst(WED), PLACED, kst(10, 8, 0, 0), AUTOMATIC, missed("[마감 리포트 누락]", false)),
                row("경계: 조기 마감일 리포트(02:10)", CLOSE_REPORT, WED, early(WED), PLACED, kst(10, 7, 2, 10), AUTOMATIC, run(false)),
                row("경계: 휴장일 리포트", CLOSE_REPORT, WED, holiday(), PLACED, kst(10, 7, 5, 30), AUTOMATIC, skip()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void decide(Row row) {
        Decision decision = new TradingBatchStep(row.kind(), row.tradeDate())
                .decide(row.session(), Optional.ofNullable(row.phase()), row.now(), row.trigger());

        row.expected().accept(decision);
    }

    private static Row row(String source, Kind kind, LocalDate tradeDate, Optional<MarketSessionTimes> session,
                           TradingBatchPhase phase, ZonedDateTime now, Trigger trigger, Consumer<Decision> expected) {
        return new Row(source, kind, tradeDate, session, phase, now.toInstant(), trigger, expected);
    }

    private static ZonedDateTime kst(int month, int day, int hour, int minute) {
        return ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, TimeZones.KST);
    }

    // DST 정규장: 전날 22:30 ~ 거래일 05:00 KST
    private static Optional<MarketSessionTimes> dst(LocalDate tradeDate) {
        return session(tradeDate, 22, 5);
    }

    // 비DST 정규장: 전날 23:30 ~ 거래일 06:00 KST
    private static Optional<MarketSessionTimes> nonDst(LocalDate tradeDate) {
        return session(tradeDate, 23, 6);
    }

    // 조기 마감일: 전날 22:30 ~ 거래일 02:00 KST
    private static Optional<MarketSessionTimes> early(LocalDate tradeDate) {
        return session(tradeDate, 22, 2);
    }

    private static Optional<MarketSessionTimes> holiday() {
        return Optional.empty();
    }

    private static Optional<MarketSessionTimes> session(LocalDate tradeDate, int openHour, int closeHour) {
        return Optional.of(new MarketSessionTimes(
                tradeDate.minusDays(1).atTime(openHour, 30).atZone(TimeZones.KST).toInstant(),
                tradeDate.atTime(closeHour, 0).atZone(TimeZones.KST).toInstant()));
    }

    private static Consumer<Decision> run(boolean warnInterrupted) {
        return decision -> assertThat(decision).isEqualTo(new Decision.Run(warnInterrupted));
    }

    private static Consumer<Decision> reserve(ZonedDateTime fireAt) {
        return decision -> assertThat(decision).isEqualTo(new Decision.Reserve(fireAt.toInstant()));
    }

    private static Consumer<Decision> skip() {
        return decision -> assertThat(decision).isInstanceOf(Decision.Skip.class);
    }

    private static Consumer<Decision> missed(String alertPrefix, boolean warnInterrupted) {
        return decision -> assertThat(decision).isInstanceOfSatisfying(Decision.Missed.class, missed -> {
            assertThat(missed.alert()).startsWith(alertPrefix);
            assertThat(missed.warnInterrupted()).isEqualTo(warnInterrupted);
        });
    }
}
