package com.kista.trading.application.service.support;

import com.kista.trading.application.event.TradingErrorEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;

// 협조적 종료 — 웹서버 graceful보다 먼저 멈춰, 배치 대기 구간은 즉시 끊고 접수·리포트 임계구역은 완료를 기다린다
// stop()은 동기 블로킹이라 timeout-per-shutdown-phase 영향 없음 — 상한(150s) + 웹 graceful(30s)이 compose stop_grace_period(200s) 안
@Slf4j
@Component
public class TradingBatchShutdownCoordinator implements SmartLifecycle {

    private final TradingBatchRunState runState;
    private final ApplicationEventPublisher eventPublisher; // 상한 초과 관리자 알림
    private final Duration criticalWait;                   // 임계구역 완료 대기 상한
    private volatile boolean running;                      // SmartLifecycle 실행 상태

    public TradingBatchShutdownCoordinator(TradingBatchRunState runState,
                                           ApplicationEventPublisher eventPublisher,
                                           @Value("${trading.shutdown.critical-wait:150s}") Duration criticalWait) {
        this.runState = runState;
        this.eventPublisher = eventPublisher;
        this.criticalWait = criticalWait;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        if (!runState.requestStop(criticalWait)) {
            log.error("매매 배치 임계구역이 {} 안에 끝나지 않음 — 강제 종료", criticalWait);
            eventPublisher.publishEvent(new TradingErrorEvent(null,
                    "[재기동] 접수/리포트 진행 중 강제 종료 — 수동 확인 필요"));
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // 웹서버 graceful(SmartLifecycle.DEFAULT_PHASE - 1024)보다 먼저 stop
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1;
    }
}
