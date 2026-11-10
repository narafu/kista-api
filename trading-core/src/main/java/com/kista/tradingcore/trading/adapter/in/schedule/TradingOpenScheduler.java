package com.kista.tradingcore.trading.adapter.in.schedule;

import com.kista.shared.platform.scheduling.SchedulerJobRunner;
import com.kista.shared.platform.scheduling.SchedulerLockService;
import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.trading.application.usecase.TradingExecutionUseCase;
import com.kista.tradingcore.trading.application.port.output.HeartbeatPort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.domain.model.TradingBatchJob;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalTime;

// 미 정규장 개장 시 order 전량 생성 + INFINITE 지정가 매도 선접수 + 예수금 부족 사용자 알람
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true) // local에서 끄면 운영 DB·텔레그램과 중복 알림 발생 방지
public class TradingOpenScheduler {
    private static final Duration LOCK_TTL = Duration.ofHours(2); // 개장 배치 락 보유시간

    static final LocalTime CRON_TIME = LocalTime.of(22, 0); // cron 발화 시각 — 아래 @Scheduled cron(22:00)과 같아야 한다(TradingBatchResumer 참조)

    private final TradingExecutionUseCase useCase;
    private final StrategyPort strategyPort;
    private final SchedulerLockService schedulerLockService;
    private final BatchContextFactory contextFactory;
    private final SchedulerJobRunner jobRunner;
    private final HeartbeatPort heartbeatPort; // dead-man's switch 핑

    @Scheduled(cron = "0 0 22 * * MON-FRI", zone = TimeZones.KST_ID) // 월~금 22:00 KST — DST 개장 30분 전(KIS DCA MOO 개장 전 접수), 비DST는 waitUntilMarketOpen 90분 대기
    public void run() throws InterruptedException {
        schedulerLockService.tryRun(TradingBatchJob.OPEN.lockName(), LOCK_TTL, this::runLocked);
    }

    // 수동 트리거 — 개장 대기 없이 즉시 실행
    public void runNow() throws InterruptedException {
        schedulerLockService.tryRun(TradingBatchJob.OPEN.lockName(), LOCK_TTL, () ->
                jobRunner.run("장 개시 스케쥴러 수동",
                        () -> contextFactory.buildAll(strategyPort.findAllActive()),
                        useCase::placeOpenOrdersNow));
    }

    // 재기동 재개 — 이전 프로세스 락을 인수해 개장 배치 재실행 (AT_OPEN slot 멱등, 이미 개장했으면 즉시 접수)
    public void resume() throws InterruptedException {
        schedulerLockService.takeOver(TradingBatchJob.OPEN.lockName(), LOCK_TTL, this::runLocked);
    }

    private void runLocked() throws InterruptedException {
        // PRIVACY 기준표 장전 점검은 TradingService가 계획 직전에 공용 가드(PrivacyBaseGuard)로 수행한다
        boolean completed = jobRunner.run("장 개시 스케쥴러",
                () -> contextFactory.buildAll(strategyPort.findAllActive()),
                useCase::placeOpenOrders);
        if (completed) heartbeatPort.pingOpen(); // 실패·인터럽트 시 미발송 — dead-man's switch가 실패도 감지
    }
}
