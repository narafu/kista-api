package com.kista.adapter.in.schedule;

import com.kista.common.TimeZones;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.privacy.PrivacyTradeValidationReport;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.port.in.PrivacyTradeValidationUseCase;
import com.kista.domain.port.in.TradingExecutionUseCase;
import com.kista.domain.port.out.NotifyPort;
import com.kista.domain.port.out.PrivacyTradePort;
import com.kista.domain.port.out.StrategyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

// 미 정규장 개장 시 order 전량 생성 + INFINITE 매도 선접수 + 예수금 부족 사용자 알람
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true) // local에서 끄면 운영 DB·텔레그램과 중복 알림 발생 방지
public class TradingOpenScheduler {

    private final TradingExecutionUseCase useCase;
    private final StrategyPort strategyPort;
    private final NotifyPort notifyPort;            // guardPrivacyStrategies 오류 알림
    private final SchedulerLockService schedulerLockService;
    private final PrivacyTradePort privacyTradePort;
    private final PrivacyTradeValidationUseCase validationService;
    private final BatchContextFactory contextFactory;
    private final SchedulerJobRunner jobRunner;

    @Scheduled(cron = "0 30 22 * * MON-FRI", zone = TimeZones.KST_ID) // 월~금 22:30 KST (DST 개장 시각, 비DST는 waitUntilMarketOpen 60분 대기)
    public void run() throws InterruptedException {
        schedulerLockService.tryRun("trading-open", Duration.ofHours(2), this::runLocked);
    }

    // 수동 트리거 — 개장 대기 없이 즉시 실행
    public void runNow() throws InterruptedException {
        LocalDate today = LocalDate.now(TimeZones.KST);
        schedulerLockService.tryRun("trading-open", Duration.ofHours(2), () ->
                jobRunner.run("장 개시 스케쥴러 수동",
                        () -> contextFactory.buildAll(guardPrivacyStrategies(strategyPort.findAllActive(), today)),
                        useCase::placeOpenOrdersNow));
    }

    private void runLocked() {
        LocalDate today = LocalDate.now(TimeZones.KST);
        jobRunner.run("장 개시 스케쥴러",
                () -> contextFactory.buildAll(guardPrivacyStrategies(strategyPort.findAllActive(), today)),
                useCase::placeOpenOrders);
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
