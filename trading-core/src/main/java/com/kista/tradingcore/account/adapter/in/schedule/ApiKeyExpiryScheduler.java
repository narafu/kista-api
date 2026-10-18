package com.kista.tradingcore.account.adapter.in.schedule;

import com.kista.shared.kernel.TimeZones;
import com.kista.shared.platform.scheduling.SchedulerLockService;
import com.kista.tradingcore.account.application.event.ApiKeyExpiringEvent;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Set;

// 거래소 API 키 만료 일일 점검 — 30·7·0일 전에만 알림 이벤트 발행
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true) // local에서 끄면 운영 DB·텔레그램과 중복 방지
public class ApiKeyExpiryScheduler {

    private static final Set<Long> NOTIFY_DAYS_LEFT = Set.of(30L, 7L, 0L); // 알림 시점(만료까지 남은 일수)

    private final AccountPort accountPort;
    private final ApplicationEventPublisher eventPublisher;
    private final SchedulerLockService schedulerLockService;
    Clock clock = Clock.system(TimeZones.KST); // 테스트에서 고정 Clock으로 교체

    @Scheduled(cron = "0 0 10 * * *", zone = TimeZones.KST_ID) // 매일 10:00 KST
    public void run() throws InterruptedException {
        schedulerLockService.tryRun("api-key-expiry", Duration.ofMinutes(30), this::publishDue);
    }

    void publishDue() {
        LocalDate today = LocalDate.now(clock.withZone(TimeZones.KST));
        accountPort.findAll().stream().filter(a -> a.apiKeyExpiresAt() != null).forEach(a -> {
            LocalDate expiresOn = a.apiKeyExpiresAt().atZone(TimeZones.KST).toLocalDate();
            long daysLeft = ChronoUnit.DAYS.between(today, expiresOn);
            if (NOTIFY_DAYS_LEFT.contains(daysLeft)) {
                eventPublisher.publishEvent(new ApiKeyExpiringEvent(
                        a.userId(), a.id(), a.nickname(), a.broker(), expiresOn, (int) daysLeft));
            }
        });
    }
}
