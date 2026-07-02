package com.kista.adapter.in.schedule;

import com.kista.common.TimeZones;
import com.kista.domain.port.in.TradingExecutionUseCase;
import com.kista.domain.port.out.StrategyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

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

    @Scheduled(cron = "0 30 4 * * TUE-SAT", zone = TimeZones.KST_ID) // 화~토 04:30 KST (DST 장마감 30분 전, 비DST는 waitUntilOrderTime 60분 대기)
    public void run() throws InterruptedException {
        schedulerLockService.tryRun("trading-close", Duration.ofHours(3), this::runLocked);
    }

    // 수동 트리거 — 주문 대기 없이 즉시 실행
    public void runNow() throws InterruptedException {
        schedulerLockService.tryRun("trading-close", Duration.ofHours(3), () ->
                jobRunner.run("마감 매매 스케쥴러 수동",
                        () -> contextFactory.buildAll(strategyPort.findAllActive()),
                        useCase::executeBatchNow));
    }

    private void runLocked() {
        // 복수종목 현재가 1회 일괄 조회 후 사이클별 순차 실행
        jobRunner.run("마감 매매 스케쥴러",
                () -> contextFactory.buildAll(strategyPort.findAllActive()),
                useCase::executeBatch);
    }
}
