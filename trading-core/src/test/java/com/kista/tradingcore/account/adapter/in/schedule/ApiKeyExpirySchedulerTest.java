package com.kista.tradingcore.account.adapter.in.schedule;

import com.kista.shared.kernel.Broker;
import com.kista.shared.platform.scheduling.SchedulerLockService;
import com.kista.tradingcore.account.application.event.ApiKeyExpiringEvent;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.account.domain.model.Account;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.kista.shared.kernel.TimeZones;
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
    // 2026-10-18 10:00 KST
    Instant now = Instant.parse("2026-10-18T01:00:00Z");
    ApiKeyExpiryScheduler scheduler = new ApiKeyExpiryScheduler(accountPort, publisher, lock, Clock.fixed(now, TimeZones.KST));

    private Account account(Instant expiresAt) {
        return new Account(UUID.randomUUID(), UUID.randomUUID(), "업비트", "n", "a", "s", null, Broker.UPBIT, now, expiresAt);
    }

    @Test
    void publishesOnlyAt30_7_0DaysLeft() {
        when(accountPort.findAll()).thenReturn(List.of(
                account(now.plus(Duration.ofDays(30))), account(now.plus(Duration.ofDays(7))), account(now),
                account(now.plus(Duration.ofDays(8))), account(null)));

        scheduler.publishDue();

        ArgumentCaptor<ApiKeyExpiringEvent> ev = ArgumentCaptor.forClass(ApiKeyExpiringEvent.class);
        verify(publisher, times(3)).publishEvent(ev.capture());
        assertThat(ev.getAllValues()).extracting(ApiKeyExpiringEvent::daysLeft).containsExactly(30, 7, 0);
    }

    @Test
    void expiryDateIsComputedInKst() {
        // 15:30Z = 다음날 00:30 KST → 오늘(10-18 KST) 기준 만료일 10-25 KST, 7일 전
        Instant expiresAt = Instant.parse("2026-10-24T15:30:00Z");
        when(accountPort.findAll()).thenReturn(List.of(account(expiresAt)));

        scheduler.publishDue();

        ArgumentCaptor<ApiKeyExpiringEvent> ev = ArgumentCaptor.forClass(ApiKeyExpiringEvent.class);
        verify(publisher).publishEvent(ev.capture());
        assertThat(ev.getValue().daysLeft()).isEqualTo(7);
        assertThat(ev.getValue().expiresOn()).isEqualTo(java.time.LocalDate.of(2026, 10, 25));
    }

    @Test
    void nonUpbitAccountIgnored() {
        // 만료일 출처가 없는 바이낸스·MOCK은 값이 있어도 무시한다
        Account binance = new Account(UUID.randomUUID(), UUID.randomUUID(), "바이낸스", "n", "a", "s", null, Broker.BINANCE, now, now);
        Account mockAcc = new Account(UUID.randomUUID(), UUID.randomUUID(), "모의", "n", "a", "s", null, Broker.MOCK, now, now);
        when(accountPort.findAll()).thenReturn(List.of(binance, mockAcc));

        scheduler.publishDue();

        org.mockito.Mockito.verifyNoInteractions(publisher);
    }

    @Test
    void meritzAccountWithExpiryIsPublished() {
        // 추정 만료일(ESTIMATED) 증권사도 점검 대상 — 메리츠·KIS 모두 발행
        Account meritz = new Account(UUID.randomUUID(), UUID.randomUUID(), "메리츠", "n", "a", "s", null, Broker.MERITZ, now, now.plus(Duration.ofDays(7)));
        Account kis = new Account(UUID.randomUUID(), UUID.randomUUID(), "KIS", "n", "a", "s", null, Broker.KIS, now, now);
        when(accountPort.findAll()).thenReturn(List.of(meritz, kis));

        scheduler.publishDue();

        ArgumentCaptor<ApiKeyExpiringEvent> ev = ArgumentCaptor.forClass(ApiKeyExpiringEvent.class);
        verify(publisher, times(2)).publishEvent(ev.capture());
        assertThat(ev.getAllValues()).extracting(ApiKeyExpiringEvent::daysLeft).containsExactly(7, 0);
    }

    @Test
    void lockNameAndDuration() throws Exception {
        scheduler.run();
        verify(lock).tryRun(eq("api-key-expiry"), eq(Duration.ofMinutes(30)), any());
    }
}
