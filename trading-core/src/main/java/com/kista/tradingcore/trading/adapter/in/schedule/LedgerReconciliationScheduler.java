package com.kista.tradingcore.trading.adapter.in.schedule;

import com.kista.shared.kernel.TimeZones;
import com.kista.shared.platform.scheduling.SchedulerLockService;
import com.kista.tradingcore.trading.application.usecase.LedgerReconciliationUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

// 전략 원장 대사 일일 점검 — 마감 리포트 후·개장 배치 전, 괴리 계좌만 관리자 알림
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true) // local에서 끄면 운영 DB·텔레그램과 중복 방지
public class LedgerReconciliationScheduler {

    private final LedgerReconciliationUseCase useCase;
    private final SchedulerLockService schedulerLockService;

    @Scheduled(cron = "0 0 9 * * TUE-SAT", zone = TimeZones.KST_ID) // 화~토 09:00 KST — 마감 리포트 후·개장 배치 전
    public void run() throws InterruptedException {
        schedulerLockService.tryRun("ledger-reconciliation", Duration.ofMinutes(30), useCase::reconcileAll);
    }
}
