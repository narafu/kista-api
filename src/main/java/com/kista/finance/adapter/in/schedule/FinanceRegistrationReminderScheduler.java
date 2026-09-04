package com.kista.finance.adapter.in.schedule;

import com.kista.platform.scheduling.SchedulerLockService;
import com.kista.common.TimeZones;
import com.kista.finance.application.usecase.FinanceRegistrationReminderUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.YearMonth;

// 매월 마지막 날 21시(KST)에 이번 달 가계부 등록이 없는 사용자에게 알림
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true)
class FinanceRegistrationReminderScheduler {

    private final FinanceRegistrationReminderUseCase notifier;
    private final SchedulerLockService schedulerLockService;

    @Scheduled(cron = "0 0 21 L * *", zone = TimeZones.KST_ID)
    void run() throws InterruptedException {
        schedulerLockService.tryRun("finance-registration-reminder", Duration.ofMinutes(30),
                () -> notifier.notifyUsersWithoutThisMonthRegistration(YearMonth.now(TimeZones.KST)));
    }
}
