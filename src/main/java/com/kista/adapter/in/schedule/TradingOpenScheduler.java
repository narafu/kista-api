package com.kista.adapter.in.schedule;

import com.kista.common.CycleLookups;
import com.kista.common.TimeZones;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.BatchContext;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.TradingExecutionUseCase;
import com.kista.domain.port.out.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

// 미 정규장 개장 시 order 전량 생성 + INFINITE 매도 선접수 + 예수금 부족 사용자 알람
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true) // local에서 끄면 운영 DB·텔레그램과 중복 알림 발생 방지
public class TradingOpenScheduler {

    private final TradingExecutionUseCase useCase;
    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final UserPort userPort;
    private final NotifyPort notifyPort;
    private final SchedulerLockService schedulerLockService;

    @Scheduled(cron = "0 0 22 * * MON-FRI", zone = TimeZones.KST_ID) // 월~금 22:00 KST (개장 30분~90분 전)
    public void run() throws InterruptedException {
        schedulerLockService.tryRun("trading-open", Duration.ofHours(2), this::runLocked);
    }

    private void runLocked() {
        notifyPort.notifyInfo("장 개시 스케쥴러 시작");
        List<Strategy> strategies = strategyPort.findAllActive();
        log.info("장 개시 스케쥴러 시작 — ACTIVE 전략 {}개", strategies.size());

        // 모든 전략(INFINITE + PRIVACY) BatchContext 빌드 — 조회 실패한 전략은 skip
        List<BatchContext> contexts = new ArrayList<>();
        for (Strategy strategy : strategies) {
            try {
                StrategyCycle currentCycle = CycleLookups.requireLatestCycle(strategyCyclePort, strategy.id());
                Account account = accountPort.findByIdOrThrow(strategy.accountId());
                User user = userPort.findByIdOrThrow(account.userId());
                contexts.add(new BatchContext(strategy, currentCycle, account, user));
            } catch (Exception e) {
                log.error("[strategyId={}] 컨텍스트 조회 오류: {}", strategy.id(), e.getMessage(), e);
                notifyPort.notifyError(e);
            }
        }

        try {
            useCase.placeOpenOrders(contexts);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("장 개시 스케쥴러 인터럽트: {}", e.getMessage());
        } catch (Exception e) {
            log.error("장 개시 스케쥴러 오류: {}", e.getMessage(), e);
            notifyPort.notifyError(e);
        }

        log.info("장 개시 스케쥴러 완료");
        notifyPort.notifyInfo("장 개시 스케쥴러 완료");
    }
}
