package com.kista.adapter.in.schedule;

import com.kista.domain.port.in.PrivacyTradeValidationUseCase;
import com.kista.common.CycleLookups;
import com.kista.common.TimeZones;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.privacy.PrivacyTradeValidationReport;
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
import java.time.LocalDate;
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
    private final PrivacyTradePort privacyTradePort;
    private final PrivacyTradeValidationUseCase validationService;

    @Scheduled(cron = "0 30 22 * * MON-FRI", zone = TimeZones.KST_ID) // 월~금 22:30 KST (DST 개장 시각, 비DST는 waitUntilMarketOpen 60분 대기)
    public void run() throws InterruptedException {
        schedulerLockService.tryRun("trading-open", Duration.ofHours(2), this::runLocked);
    }

    // 수동 트리거 — 개장 대기 없이 즉시 실행
    public void runNow() throws InterruptedException {
        schedulerLockService.tryRun("trading-open", Duration.ofHours(2), () -> {
            notifyPort.notifyInfo("장 개시 스케쥴러 수동 시작");
            List<BatchContext> contexts = buildContexts();
            log.info("장 개시 스케쥴러 수동 시작 — ACTIVE 전략 {}개", contexts.size());
            try {
                useCase.placeOpenOrdersNow(contexts);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("장 개시 스케쥴러 수동 인터럽트: {}", e.getMessage());
            } catch (Exception e) {
                log.error("장 개시 스케쥴러 수동 오류: {}", e.getMessage(), e);
                notifyPort.notifyError(e);
            }
            log.info("장 개시 스케쥴러 수동 완료");
            notifyPort.notifyInfo("장 개시 스케쥴러 수동 완료");
        });
    }

    private void runLocked() {
        notifyPort.notifyInfo("장 개시 스케쥴러 시작");
        List<BatchContext> contexts = buildContexts();
        log.info("장 개시 스케쥴러 시작 — ACTIVE 전략 {}개", contexts.size());

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

    // 모든 전략(INFINITE + PRIVACY) BatchContext 빌드 — 조회 실패한 전략은 skip
    private List<BatchContext> buildContexts() {
        List<Strategy> strategies = strategyPort.findAllActive();
        LocalDate today = LocalDate.now(TimeZones.KST);
        strategies = guardPrivacyStrategies(strategies, today);
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
        return contexts;
    }

    // PRIVACY 기준 매매표가 위험 패턴이면 그 실행에서만 주문 생성 skip + 관리자 알림
    private List<Strategy> guardPrivacyStrategies(List<Strategy> strategies, LocalDate today) {
        List<Strategy> privacyStrategies = strategies.stream()
                .filter(Strategy::isPrivacy)
                .toList();
        if (privacyStrategies.isEmpty()) return strategies;

        PrivacyTradeBase base = privacyTradePort.findTodayTrade(today).orElse(null);
        if (base == null) return strategies;

        PrivacyTradeValidationReport report = validationService.inspect(base);
        if (!report.hasIssues()) return strategies;

        notifyPort.notifyError(new IllegalStateException(
                "[PRIVACY] 장전 가드 발동 — 기준 매매표 이상으로 주문 생성 skip: " + report.summary()));
        return strategies.stream()
                .filter(s -> !s.isPrivacy())
                .toList();
    }
}
