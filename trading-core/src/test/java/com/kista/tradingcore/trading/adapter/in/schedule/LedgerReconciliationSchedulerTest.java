package com.kista.tradingcore.trading.adapter.in.schedule;

import com.kista.shared.platform.scheduling.SchedulerLockService;
import com.kista.tradingcore.trading.application.usecase.LedgerReconciliationUseCase;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LedgerReconciliationSchedulerTest {

    LedgerReconciliationUseCase useCase = mock(LedgerReconciliationUseCase.class);
    SchedulerLockService lock = mock(SchedulerLockService.class);
    LedgerReconciliationScheduler scheduler = new LedgerReconciliationScheduler(useCase, lock);

    @Test
    void run_은_락_이름과_TTL로_유스케이스를_실행한다() throws Exception {
        doAnswer(inv -> {
            inv.<SchedulerLockService.LockedTask>getArgument(2).run();
            return true;
        }).when(lock).tryRun(eq("ledger-reconciliation"), eq(Duration.ofMinutes(30)), any());

        scheduler.run();

        verify(lock).tryRun(eq("ledger-reconciliation"), eq(Duration.ofMinutes(30)), any());
        verify(useCase).reconcileAll();
    }
}
