package com.kista.tradingcore.trading.adapter.in.schedule;

import com.kista.shared.platform.scheduling.SchedulerJobRunner;
import com.kista.shared.platform.scheduling.SchedulerLockService;
import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.trading.application.usecase.TradingExecutionUseCase;
import com.kista.tradingcore.trading.application.port.output.HeartbeatPort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.application.port.output.TradingBatchRunPort;
import com.kista.tradingcore.trading.application.port.output.TradingErrorReportPort;
import com.kista.tradingcore.trading.domain.model.TradingBatchJob;
import com.kista.tradingcore.trading.domain.model.TradingBatchPhase;
import com.kista.tradingcore.trading.domain.model.Strategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true) // local에서 끄면 운영 DB·텔레그램과 중복 알림 발생 방지
public class TradingCloseScheduler {

    private final TradingExecutionUseCase useCase;
    private final StrategyPort strategyPort;        // ACTIVE 전략 목록 조회
    private final SchedulerLockService schedulerLockService;
    private final BatchContextFactory contextFactory;
    private final SchedulerJobRunner jobRunner;
    private final HeartbeatPort heartbeatPort; // dead-man's switch 핑
    private final TradingErrorReportPort errorReportPort; // 수동 트리거 거부 관리자 알림
    private final Clock clock; // 현재 시각 기준 — 테스트에서 Clock.fixed 주입
    private final MarketCalendarPort marketCalendarPort;
    private final TradingBatchRunPort batchRunPort;
    private final TaskScheduler taskScheduler;
    private final TradingBatchSerialExecutor serialExecutor;
    private final Map<LocalDate, Reservation> reservations = new HashMap<>(); // 거래일별 메모리 예약 — 재기동 시 TradingBatchResumer·reconcile이 재등록

    private static final Duration LOCK_TTL = Duration.ofHours(3); // 마감 배치 락 보유시간
    static final Duration ORDER_MARGIN = Duration.ofMinutes(30);     // 실제 마감 30분 전 접수
    static final Duration PLACEMENT_MARGIN = Duration.ofMinutes(10); // 실제 마감 10분 전까지만 접수
    private static final Duration EARLY_FIRE_TOLERANCE = Duration.ofMinutes(1); // 예약 지연은 단조 시계라 벽시계보다 조금 일찍 발화할 수 있음
    private static final Duration AUTOMATIC_WINDOW = ORDER_MARGIN.plus(EARLY_FIRE_TOLERANCE); // 자동 실행 접수 창 시작
    private static final Duration MANUAL_WINDOW = Duration.ofMinutes(90);       // 수동 마감 허용 시작 — 기존 비DST 04:30(마감 90분 전)과 같은 폭

    private record Reservation(Instant orderAt, ScheduledFuture<?> future) {}

    // 정시 개장 및 기동 복구가 같은 거래일을 예약해도 콜백은 하나만 등록한다 — 예약 시각이 이미 지났으면 관리자 알림
    void schedule(LocalDate tradeDate) {
        register(tradeDate, true);
    }

    // 예약 누락·마감 시각 변경 보정 — 매시 오늘·내일 예약을 다시 맞춘다(이미 지난 예약 시각은 조용히 건너뜀)
    @Scheduled(cron = "0 5 * * * *", zone = TimeZones.KST_ID)
    public void reconcile() {
        LocalDate today = LocalDate.now(clock.withZone(TimeZones.KST));
        register(today, false);
        register(today.plusDays(1), false);
    }

    // healthchecks.io 마감 체크는 cron(화~토 04:30 KST)이라 그 시각에 배치가 없는 날 핑이 끊긴다 — 휴장일(예약 없음)과 조기 마감일(02:30대 핑 뒤에도 04:30을 기대)에 대신 보낸다
    // 정상 거래일은 배치 완료 핑이 이 시각 이후(비DST 05:30)에 오므로 여기서는 건너뛰고, 세션 조회 실패(캘린더 미적재)도 핑을 보내지 않아 알림으로 드러낸다
    @Scheduled(cron = "0 30 4 * * TUE-SAT", zone = TimeZones.KST_ID)
    public void pingHeartbeatSlot() {
        try {
            LocalDate today = LocalDate.now(clock.withZone(TimeZones.KST));
            boolean noBatchPending = marketCalendarPort.findSession(today).isEmpty()
                    || batchRunPort.findPhase(TradingBatchJob.CLOSE, today).orElse(null) == TradingBatchPhase.DONE;
            if (noBatchPending) heartbeatPort.pingClose();
        } catch (RuntimeException e) {
            log.warn("마감 하트비트 판정 실패 — 핑 생략: {}", e.getMessage());
        }
    }

    private synchronized void register(LocalDate tradeDate, boolean alertIfPast) {
        try {
            var session = marketCalendarPort.findSession(tradeDate);
            Reservation existing = reservations.get(tradeDate);
            Instant orderAt = session.map(s -> s.closeAt().minus(ORDER_MARGIN)).orElse(null);
            if (existing != null) {
                // 같은 시각이거나 이미 발화(실행 중 포함)한 예약은 건드리지 않는다 — 실행 중 취소·재등록하면 이중 실행된다
                if (existing.orderAt().equals(orderAt) || existing.future().isDone()
                        || !clock.instant().isBefore(existing.orderAt())) return;
                // 캘린더 갱신으로 마감 시각이 바뀌었거나 휴장이 됐으면 기존 예약을 취소하고 다시 등록한다
                existing.future().cancel(false);
                reservations.remove(tradeDate);
                log.warn("마감 예약 변경 — 거래일 {}: {} → {}", tradeDate, existing.orderAt(), orderAt);
            }
            if (orderAt == null || batchRunPort.findPhase(TradingBatchJob.CLOSE, tradeDate)
                    .orElse(null) == TradingBatchPhase.DONE) return;
            if (!clock.instant().isBefore(orderAt)) {
                if (alertIfPast) alert("[마감 예약 누락] 예약 시각 경과 — 거래일 " + tradeDate);
                return;
            }
            ScheduledFuture<?> future = taskScheduler.schedule(() -> scheduledCallback(tradeDate), orderAt);
            if (future == null) throw new IllegalStateException("마감 예약이 등록되지 않았습니다: " + tradeDate);
            reservations.put(tradeDate, new Reservation(orderAt, future));
            reservations.entrySet().removeIf(entry -> entry.getKey().isBefore(tradeDate) && entry.getValue().future().isDone());
        } catch (Exception e) {
            report("마감 예약 실패 (거래일 " + tradeDate + ")", e);
        }
    }

    private void scheduledCallback(LocalDate tradeDate) {
        try {
            serialExecutor.run(() -> {
                if (!schedulerLockService.tryRun(TradingBatchJob.CLOSE.lockName(), LOCK_TTL,
                        () -> runAutomatic(tradeDate))
                        && batchRunPort.findPhase(TradingBatchJob.CLOSE, tradeDate).orElse(null) != TradingBatchPhase.DONE) {
                    alert("[마감 예약 누락] 락 획득 실패 — 거래일 " + tradeDate);
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            report("마감 예약 실행 인터럽트 (거래일 " + tradeDate + ")", e);
        } catch (Exception e) {
            report("마감 예약 실행 실패 (거래일 " + tradeDate + ")", e);
        }
    }

    // 수동 트리거 — 주문 대기 없이 즉시 접수, 리포트는 장마감 후
    public void runNow() throws InterruptedException {
        LocalDate tradeDate = LocalDate.now(clock.withZone(TimeZones.KST));
        if (!canPlace(tradeDate, MANUAL_WINDOW)) return;
        serialExecutor.run(() -> {
            if (!canPlace(tradeDate, MANUAL_WINDOW)) return;
            schedulerLockService.tryRun(TradingBatchJob.CLOSE.lockName(), LOCK_TTL, () -> {
                if (!canPlace(tradeDate, MANUAL_WINDOW)) return;
                runBatch("마감 매매 스케쥴러 수동", tradeDate, MANUAL_WINDOW);
            });
        });
    }

    // 재기동 재개 — 이전 프로세스 락을 인수해 마감 배치 전체 재실행 (slot 멱등 재계획, 남은 시간만 대기)
    public void resume() throws InterruptedException {
        LocalDate tradeDate = LocalDate.now(clock.withZone(TimeZones.KST));
        serialExecutor.run(() -> schedulerLockService.takeOver(TradingBatchJob.CLOSE.lockName(), LOCK_TTL,
                () -> runAutomatic(tradeDate)));
    }

    // 재기동 재개 — 접수 완료(PLACED) 이후 중단분: 리포트 미완료 전략만 리포트
    public void resumeReport() throws InterruptedException {
        LocalDate tradeDate = LocalDate.now(clock.withZone(TimeZones.KST));
        serialExecutor.run(() -> schedulerLockService.takeOver(TradingBatchJob.CLOSE.lockName(), LOCK_TTL,
                () -> runReportIfPlaced(tradeDate)));
    }

    // 마감 배치 대상 = ACTIVE + 정지·보관됐지만 당일 접수 주문이 있는 전략(체결 리포트 전용) — 중복은 ACTIVE 우선
    private List<Strategy> closeBatchStrategies() {
        List<Strategy> active = strategyPort.findAllActive();
        Set<UUID> activeIds = active.stream().map(Strategy::id).collect(Collectors.toSet());
        return Stream.concat(active.stream(),
                        strategyPort.findNotActiveWithReportableOrders(LocalDate.now(clock.withZone(TimeZones.KST))).stream()
                                .filter(s -> !activeIds.contains(s.id())))
                .toList();
    }

    private void runAutomatic(LocalDate tradeDate) throws InterruptedException {
        TradingBatchPhase phase = batchRunPort.findPhase(TradingBatchJob.CLOSE, tradeDate).orElse(null);
        if (phase == TradingBatchPhase.DONE) return;
        if (phase == TradingBatchPhase.PLACED) {
            runReportIfPlaced(tradeDate);
            return;
        }
        if (phase == TradingBatchPhase.PLACING) alert("[재개 경고] 마감 접수 도중 중단 — 이중 접수 확인 필요 (거래일 " + tradeDate + ")");
        if (!canPlace(tradeDate, AUTOMATIC_WINDOW)) return;
        runBatch("마감 매매 스케쥴러", tradeDate, AUTOMATIC_WINDOW);
    }

    private void runBatch(String name, LocalDate tradeDate, Duration earliest) throws InterruptedException {
        boolean completed = jobRunner.run(name,
                () -> contextFactory.buildAll(closeBatchStrategies()),
                contexts -> {
                    if (!canPlace(tradeDate, earliest)) throw new IllegalStateException("마감 접수 창 경과: " + tradeDate);
                    useCase.executeBatchNow(contexts);
                });
        if (completed) heartbeatPort.pingClose(); // 실패·인터럽트 시 미발송 — dead-man's switch가 실패도 감지
    }

    private void runReportIfPlaced(LocalDate tradeDate) throws InterruptedException {
        if (!tradeDate.equals(LocalDate.now(clock.withZone(TimeZones.KST)))) {
            alert("[마감 리포트 누락] 거래일 경과 — " + tradeDate);
            return;
        }
        if (batchRunPort.findPhase(TradingBatchJob.CLOSE, tradeDate).orElse(null) != TradingBatchPhase.PLACED) return;
        if (marketCalendarPort.findSession(tradeDate).isEmpty()) return;
        boolean completed = jobRunner.run("마감 리포트 재개",
                () -> contextFactory.buildAll(closeBatchStrategies()), useCase::resumeCloseReport);
        if (completed) heartbeatPort.pingClose();
    }

    // 접수 창: 마감 earliest 전 ~ 마감 10분 전, KST 당일 거래일만
    private boolean canPlace(LocalDate tradeDate, Duration earliest) {
        try {
            var session = marketCalendarPort.findSession(tradeDate);
            if (session.isEmpty()) return false;
            Instant now = clock.instant();
            if (!tradeDate.equals(LocalDate.now(clock.withZone(TimeZones.KST)))
                    || now.isBefore(session.get().closeAt().minus(earliest))
                    || !now.isBefore(session.get().closeAt().minus(PLACEMENT_MARGIN))) {
                alert("[마감 접수 불가] 접수 창 밖 또는 거래일 경과 — " + tradeDate + ", 시각 " + now);
                return false;
            }
            return true;
        } catch (Exception e) {
            report("마감 세션 조회 실패 (거래일 " + tradeDate + ")", e);
            return false;
        }
    }

    private void alert(String message) {
        report(message, new IllegalStateException(message));
    }

    private void report(String message, Exception e) {
        log.error(message, e);
        try {
            errorReportPort.reportError(new IllegalStateException(message, e));
        } catch (Exception notificationFailure) {
            log.error("관리자 알림 실패", notificationFailure);
        }
    }
}
