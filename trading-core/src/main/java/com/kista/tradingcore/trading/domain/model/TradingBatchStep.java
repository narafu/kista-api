package com.kista.tradingcore.trading.domain.model;

import com.kista.shared.kernel.TimeZones;
import com.kista.shared.platform.time.KstTradeDates;
import com.kista.tradingcore.marketcalendar.domain.model.MarketSessionTimes;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

// 매매 배치 단계와 실행 판정표 — (세션, 체크포인트, 현재 시각, 트리거)만으로 지금 할 일을 정한다(Spring·DB·시계 의존 없음)
// 규칙은 TradingOpenScheduler·TradingCloseScheduler·TradingBatchResumer의 현재 동작을 옮긴 것이고 아직 어느 스케줄러도 이 표를 쓰지 않는다
public record TradingBatchStep(Kind kind, LocalDate tradeDate) {

    static final Duration ORDER_MARGIN = Duration.ofMinutes(30);        // 마감 30분 전 접수 시작(개장 접수 창의 끝이기도 하다)
    static final Duration PLACEMENT_MARGIN = Duration.ofMinutes(10);    // 마감 10분 전까지만 접수
    static final Duration EARLY_FIRE_TOLERANCE = Duration.ofMinutes(1); // 예약이 벽시계보다 조금 일찍 발화해도 실행
    static final Duration MANUAL_WINDOW = Duration.ofMinutes(90);       // 수동 마감 접수 허용 시작
    static final Duration REPORT_DELAY = Duration.ofMinutes(10);        // 마감 10분 뒤 체결 리포트
    static final LocalTime OPEN_TRADE_DATE_CUTOFF = KstTradeDates.SCHEDULER_RUN_TIME; // 개장 배치의 거래일 경계(04:30 KST) — 세션 기반 통일 때 KstTradeDates와 함께 없앤다

    public enum Kind {
        OPEN_PLACE(TradingBatchJob.OPEN),    // 개장 시각에 AT_OPEN 주문 접수
        CLOSE_PLACE(TradingBatchJob.CLOSE),  // 마감 30분 전 AT_CLOSE 주문 접수
        CLOSE_REPORT(TradingBatchJob.CLOSE); // 마감 10분 뒤 체결 조회·원장 기록

        private final TradingBatchJob job; // 체크포인트·락 키

        Kind(TradingBatchJob job) {
            this.job = job;
        }

        public TradingBatchJob job() {
            return job;
        }
    }

    public enum Trigger {
        AUTOMATIC, // 예약 발화·매시 보정·기동 재개
        MANUAL     // 관리자 수동 실행
    }

    public sealed interface Decision {
        // warnInterrupted: 이전 실행이 접수 도중(PLACING) 끊겼다 — 이중 접수 확인 알림을 함께 낸다
        record Run(boolean warnInterrupted) implements Decision {}

        record Reserve(Instant fireAt) implements Decision {}

        // 휴장, 이미 끝난 단계, 접수가 끝나지 않은 리포트 — 알림 없음
        record Skip(String reason) implements Decision {}

        // 실행 창 밖 — alert는 관리자 알림 문구
        record Missed(String alert, boolean warnInterrupted) implements Decision {}
    }

    public Decision decide(Optional<MarketSessionTimes> session, Optional<TradingBatchPhase> phase,
                           Instant now, Trigger trigger) {
        // 수동 개장 실행은 지금 세션·시각·체크포인트를 보지 않는다
        if (kind == Kind.OPEN_PLACE && trigger == Trigger.MANUAL) return new Decision.Run(false);
        if (session.isEmpty()) return new Decision.Skip("휴장");
        Instant closeAt = session.get().closeAt();
        TradingBatchPhase current = phase.orElse(null);
        return switch (kind) {
            case OPEN_PLACE -> openPlace(session.get(), current, now);
            case CLOSE_PLACE -> trigger == Trigger.MANUAL ? manualClosePlace(closeAt, now) : closePlace(closeAt, current, now);
            case CLOSE_REPORT -> closeReport(closeAt, current, now);
        };
    }

    // 창: 개장 ~ min(마감 30분 전, 거래일 04:30 KST)
    private Decision openPlace(MarketSessionTimes session, TradingBatchPhase phase, Instant now) {
        if (phase == TradingBatchPhase.DONE) return new Decision.Skip("개장 배치 완료");
        if (now.isBefore(session.openAt())) return new Decision.Reserve(session.openAt());
        boolean interrupted = phase == TradingBatchPhase.PLACING;
        Instant cutoff = tradeDate.atTime(OPEN_TRADE_DATE_CUTOFF).atZone(TimeZones.KST).toInstant();
        Instant end = session.closeAt().minus(ORDER_MARGIN);
        if (!now.isBefore(end.isBefore(cutoff) ? end : cutoff)) {
            return new Decision.Missed("[개장 재개 누락] 거래일 또는 시각 경과 — " + tradeDate, interrupted);
        }
        return new Decision.Run(interrupted);
    }

    // 창: 마감 31분 전 ~ 마감 10분 전
    private Decision closePlace(Instant closeAt, TradingBatchPhase phase, Instant now) {
        if (phase == TradingBatchPhase.PLACED || phase == TradingBatchPhase.DONE) return new Decision.Skip("마감 접수 완료");
        Instant orderAt = closeAt.minus(ORDER_MARGIN);
        if (now.isBefore(orderAt.minus(EARLY_FIRE_TOLERANCE))) return new Decision.Reserve(orderAt);
        boolean interrupted = phase == TradingBatchPhase.PLACING;
        if (!now.isBefore(closeAt.minus(PLACEMENT_MARGIN))) {
            return new Decision.Missed("[재개 불가] trading-close 접수 마감 경과 (phase=" + phase + ", 거래일 " + tradeDate
                    + ") — 마감 매매 미접수, 수동 확인 필요", interrupted);
        }
        return new Decision.Run(interrupted);
    }

    // 창: 마감 90분 전 ~ 마감 10분 전 — 수동 실행은 체크포인트를 보지 않고 다시 돈다(슬롯 멱등)
    private Decision manualClosePlace(Instant closeAt, Instant now) {
        if (now.isBefore(closeAt.minus(MANUAL_WINDOW)) || !now.isBefore(closeAt.minus(PLACEMENT_MARGIN))) {
            return new Decision.Missed("[마감 접수 불가] 접수 창 밖 또는 거래일 경과 — " + tradeDate + ", 시각 " + now, false);
        }
        return new Decision.Run(false);
    }

    // 창: 마감 10분 뒤 ~ 거래일 KST 자정, 접수가 끝난(PLACED) 날만
    private Decision closeReport(Instant closeAt, TradingBatchPhase phase, Instant now) {
        if (phase == TradingBatchPhase.DONE) return new Decision.Skip("마감 리포트 완료");
        Instant reportAt = closeAt.plus(REPORT_DELAY);
        if (now.isBefore(reportAt)) return new Decision.Reserve(reportAt);
        if (phase != TradingBatchPhase.PLACED) return new Decision.Skip("마감 접수 미완료");
        if (!now.isBefore(tradeDate.plusDays(1).atStartOfDay(TimeZones.KST).toInstant())) {
            return new Decision.Missed("[마감 리포트 누락] 거래일 경과 — " + tradeDate, false);
        }
        return new Decision.Run(false);
    }
}
