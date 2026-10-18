package com.kista.tradingcore.account.adapter.in.schedule;

import com.kista.shared.kernel.Broker;
import com.kista.shared.platform.scheduling.SchedulerLockService;
import com.kista.tradingcore.account.application.event.ApiKeyExpiringEvent;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.account.domain.model.Account;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyExpirySchedulerTest {

    AccountPort accountPort = mock(AccountPort.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    SchedulerLockService lock = mock(SchedulerLockService.class);
    ApiKeyExpiryScheduler scheduler = new ApiKeyExpiryScheduler(accountPort, publisher, lock);

    // 2026-10-18 10:00 KST
    Instant now = Instant.parse("2026-10-18T01:00:00Z");

    private Account account(Instant expiresAt) {
        return new Account(UUID.randomUUID(), UUID.randomUUID(), "업비트", "n", "a", "s", null, Broker.UPBIT, now, expiresAt);
    }

    @Test
    void publishesOnlyAt30_7_0DaysLeft() {
        scheduler.clock = Clock.fixed(now, ZoneOffset.UTC);
        when(accountPort.findAll()).thenReturn(List.of(
                account(now.plus(Duration.ofDays(30))), account(now.plus(Duration.ofDays(7))), account(now),
                account(now.plus(Duration.ofDays(8))), account(null)));

        scheduler.publishDue();

        ArgumentCaptor<ApiKeyExpiringEvent> ev = ArgumentCaptor.forClass(ApiKeyExpiringEvent.class);
        verify(publisher, times(3)).publishEvent(ev.capture());
        assertThat(ev.getAllValues()).extracting(ApiKeyExpiringEvent::daysLeft).containsExactly(30, 7, 0);
    }

    @Test
    void nonUpbitAccountIgnored() {
        scheduler.clock = Clock.fixed(now, ZoneOffset.UTC);
        Account kis = new Account(UUID.randomUUID(), UUID.randomUUID(), "KIS", "n", "a", "s", null, Broker.KIS, now, now);
        when(accountPort.findAll()).thenReturn(List.of(kis));

        scheduler.publishDue();

        verify(publisher, org.mockito.Mockito.never()).publishEvent(any(Object.class));
    }

    @Test
    void lockNameAndDuration() throws Exception {
        scheduler.run();
        verify(lock).tryRun(eq("api-key-expiry"), eq(Duration.ofMinutes(30)), any());
    }
}
