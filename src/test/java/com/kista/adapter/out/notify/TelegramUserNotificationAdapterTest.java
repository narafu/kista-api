package com.kista.adapter.out.notify;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.TradingReport;
import com.kista.domain.model.strategy.TradingSnapshot;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.user.NotificationChannel;
import com.kista.domain.model.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramUserNotificationAdapterTest {

    @Mock RestTemplate restTemplate;

    TelegramUserNotificationAdapter adapter;

    static final TelegramProperties PROPS = new TelegramProperties("admin-token", "admin-chat");

    @BeforeEach
    void setUp() {
        TelegramHttpClient httpClient = new TelegramHttpClient(restTemplate);
        adapter = new TelegramUserNotificationAdapter(httpClient, PROPS);
    }

    @Test
    void notifyStrategyChanged_sendsToAdminChat() {
        User user = new User(UUID.randomUUID(), "kakao-1", "홍길동", User.UserStatus.ACTIVE, User.UserRole.USER,
                null, null, null, Instant.now(), Instant.now(), null, NotificationChannel.TELEGRAM);
        Account account = mock(Account.class);
        when(account.nickname()).thenReturn("내계좌");
        TradingCycle cycle = new TradingCycle(UUID.randomUUID(), UUID.randomUUID(),
                TradingCycle.Type.INFINITE, TradingCycle.Status.ACTIVE, TradingCycle.Ticker.SOXL,
                null, Instant.now(), Instant.now());

        adapter.notifyStrategyChanged(user, account, cycle, "중지");

        verify(restTemplate).postForObject(contains("/botadmin-token/sendMessage"), any(), eq(String.class));
    }

    @Test
    void notifyTradingReport_withUserBot_sendsToUserChatId() {
        User user = new User(UUID.randomUUID(), "kakao-1", "홍길동", User.UserStatus.ACTIVE, User.UserRole.USER,
                "user-bot-token", "user-chat-789", null, Instant.now(), Instant.now(), null, NotificationChannel.TELEGRAM);
        Account account = mock(Account.class);
        when(account.nickname()).thenReturn("SOXL계좌");

        adapter.notifyTradingReport(user, account, buildTestReport());

        verify(restTemplate).postForObject(contains("/botuser-bot-token/sendMessage"), any(), eq(String.class));
    }

    @Test
    void notifyTradingReport_noUserBot_skips() {
        User user = new User(UUID.randomUUID(), "kakao-1", "홍길동", User.UserStatus.ACTIVE, User.UserRole.USER,
                null, null, null, Instant.now(), Instant.now(), null, NotificationChannel.TELEGRAM);
        Account account = mock(Account.class);
        when(account.nickname()).thenReturn("노봇계좌");

        adapter.notifyTradingReport(user, account, buildTestReport());

        verify(restTemplate, never()).postForObject(any(), any(), any());
    }

    // TradingReport 생성 헬퍼
    private TradingReport buildTestReport() {
        TradingSnapshot snapshot = new TradingSnapshot(10,
                new BigDecimal("20.00"), new BigDecimal("0.1733"), new BigDecimal("24.00"));
        return new TradingReport(
                LocalDate.of(2024, 6, 15), snapshot, List.of(), List.of(),
                new BigDecimal("66.00"), new BigDecimal("35.00"));
    }
}
