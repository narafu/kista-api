package com.kista.trading.adapter.in.schedule;

import com.kista.platform.scheduling.SchedulerJobRunner;
import com.kista.platform.scheduling.SchedulerLockService;
import com.kista.sharedkernel.TimeZones;
import com.kista.privacy.domain.model.PrivacyTradeBase;
import com.kista.privacy.domain.model.PrivacyTradeValidationReport;
import com.kista.trading.domain.model.Strategy;
import com.kista.trading.domain.model.DstInfo;
import com.kista.privacy.application.usecase.PrivacyTradeValidationUseCase;
import com.kista.trading.application.usecase.TradingExecutionUseCase;
import com.kista.trading.application.port.output.HeartbeatPort;
import com.kista.trading.application.port.output.TradingErrorReportPort;
import com.kista.privacy.application.port.output.PrivacyTradePort;
import com.kista.trading.application.port.output.StrategyPort;
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
    private final TradingErrorReportPort errorReportPort;  // guardPrivacyStrategies 오류 알림 (출력 포트 경유)
    private final SchedulerLockService schedulerLockService;
    private final PrivacyTradePort privacyTradePort;
    private final PrivacyTradeValidationUseCase validationService;
    private final BatchContextFactory contextFactory;
    private final SchedulerJobRunner jobRunner;
    private final HeartbeatPort heartbeatPort; // dead-man's switch 핑

    @Scheduled(cron = "0 30 22 * * MON-FRI", zone = TimeZones.KST_ID) // 월~금 22:30 KST (DST 개장 시각, 비DST는 waitUntilMarketOpen 60분 대기)
    public void run() throws InterruptedException {
        schedulerLockService.tryRun("trading-open", Duration.ofHours(2), this::runLocked);
    }

    // 수동 트리거 — 개장 대기 없이 즉시 실행
    public void runNow() throws InterruptedException {
        LocalDate tradeDate = DstInfo.nextTradeDate(); // PRIVACY 가드 조회 기준 — KST 거래일 (findTodayTrade 계약)
        schedulerLockService.tryRun("trading-open", Duration.ofHours(2), () ->
                jobRunner.run("장 개시 스케쥴러 수동",
                        () -> contextFactory.buildAll(guardPrivacyStrategies(strategyPort.findAllActive(), tradeDate)),
                        useCase::placeOpenOrdersNow));
    }

    // 재기동 재개 — 이전 프로세스 락을 인수해 개장 배치 재실행 (AT_OPEN slot 멱등, 이미 개장했으면 즉시 접수)
    public void resume() throws InterruptedException {
        schedulerLockService.takeOver("trading-open", Duration.ofHours(2), this::runLocked);
    }

    private void runLocked() throws InterruptedException {
        LocalDate tradeDate = DstInfo.nextTradeDate(); // PRIVACY 가드 조회 기준 — 자정 이후 재개에도 거래일 정합
        jobRunner.run("장 개시 스케쥴러",
                () -> contextFactory.buildAll(guardPrivacyStrategies(strategyPort.findAllActive(), tradeDate)),
                useCase::placeOpenOrders);
        heartbeatPort.pingOpen(); // 인터럽트 시 도달 안 함 — 실행 완료 신호만 발송
    }

    // PRIVACY 기준 매매표가 위험 패턴이면 그 실행에서만 주문 생성 skip + 관리자 알림
    private List<Strategy> guardPrivacyStrategies(List<Strategy> strategies, LocalDate tradeDate) {
        List<Strategy> privacyStrategies = strategies.stream()
                .filter(Strategy::isPrivacy)
                .toList();
        if (privacyStrategies.isEmpty()) return strategies;

        PrivacyTradeBase base = privacyTradePort.findTodayTrade(tradeDate).orElse(null);
        if (base == null) return strategies;

        PrivacyTradeValidationReport report = validationService.inspect(base);
        if (!report.hasIssues()) return strategies;

        errorReportPort.reportError(new IllegalStateException(
                "[PRIVACY] 장전 가드 발동 — 기준 매매표 이상으로 주문 생성 skip: " + report.summary()));
        return strategies.stream()
                .filter(s -> !s.isPrivacy())
                .toList();
    }
}
