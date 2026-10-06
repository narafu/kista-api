package com.kista.trading.adapter.in.schedule;

import com.kista.platform.scheduling.SchedulerJobRunner;
import com.kista.platform.scheduling.SchedulerLockService;
import com.kista.sharedkernel.TimeZones;
import com.kista.trading.application.usecase.TradingExecutionUseCase;
import com.kista.trading.application.port.output.HeartbeatPort;
import com.kista.trading.application.port.output.StrategyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

// 미 정규장 개장 시 order 전량 생성 + INFINITE 매도 선접수 + 예수금 부족 사용자 알람
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true) // local에서 끄면 운영 DB·텔레그램과 중복 알림 발생 방지
public class TradingOpenScheduler {

    private final TradingExecutionUseCase useCase;
    private final StrategyPort strategyPort;
    private final SchedulerLockService schedulerLockService;
    private final BatchContextFactory contextFactory;
    private final SchedulerJobRunner jobRunner;
    private final HeartbeatPort heartbeatPort; // dead-man's switch 핑

    @Scheduled(cron = "0 30 22 * * MON-FRI", zone = TimeZones.KST_ID) // 월~금 22:30 KST (DST 개장 시각, 비DST는 waitUntilMarketOpen 60분 대기)
    public void run() throws InterruptedException {
        schedulerLockService.tryRun("trading-open", Duration.ofHours(2), this::runLocked);
    }

    // 수동 트리거 — 개장 대기 없이 즉시 실행
    public void runNow() throws InterruptedException {
        schedulerLockService.tryRun("trading-open", Duration.ofHours(2), () ->
                jobRunner.run("장 개시 스케쥴러 수동",
                        () -> contextFactory.buildAll(strategyPort.findAllActive()),
                        useCase::placeOpenOrdersNow));
    }

    // 재기동 재개 — 이전 프로세스 락을 인수해 개장 배치 재실행 (AT_OPEN slot 멱등, 이미 개장했으면 즉시 접수)
    public void resume() throws InterruptedException {
        schedulerLockService.takeOver("trading-open", Duration.ofHours(2), this::runLocked);
    }

    private void runLocked() throws InterruptedException {
        // PRIVACY 기준표 장전 점검은 TradingService가 계획 직전에 공용 가드(PrivacyBaseGuard)로 수행한다
        jobRunner.run("장 개시 스케쥴러",
                () -> contextFactory.buildAll(strategyPort.findAllActive()),
                useCase::placeOpenOrders);
        heartbeatPort.pingOpen(); // 인터럽트 시 도달 안 함 — 실행 완료 신호만 발송
    }
}
