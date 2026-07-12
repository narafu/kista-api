package com.kista.adapter.out.notify;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.TradingReport;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    void notifyTradingReport_withUserBot_sendsToUserChatId() {
        User user = DomainFixtures.telegramUser(UUID.randomUUID(), "user-bot-token", "user-chat-789");
        Account account = mock(Account.class);
        when(account.nickname()).thenReturn("SOXL계좌");

        adapter.notifyTradingReport(user, account, buildTestReport());

        verify(restTemplate).postForObject(contains("/botuser-bot-token/sendMessage"), any(), eq(String.class));
    }

    @Test
    void notifyTradingReport_noUserBot_skips() {
        User user = DomainFixtures.activeUser(UUID.randomUUID(), NotificationChannel.TELEGRAM);
        Account account = mock(Account.class);

        adapter.notifyTradingReport(user, account, buildTestReport());

        verify(restTemplate, never()).postForObject(any(), any(), any());
    }

    @Test
    void notifyBatchInterrupted_withUserBot_sendsToUserChatId() {
        User user = DomainFixtures.telegramUser(UUID.randomUUID(), "user-bot-token", "user-chat-789");
        Account account = mock(Account.class);
        when(account.nickname()).thenReturn("SOXL계좌");

        adapter.notifyBatchInterrupted(user, account);

        verify(restTemplate).postForObject(contains("/botuser-bot-token/sendMessage"), any(), eq(String.class));
    }

    // TradingReport 생성 헬퍼
    private TradingReport buildTestReport() {
        return new TradingReport(LocalDate.of(2024, 6, 15), Strategy.Type.INFINITE, Strategy.Ticker.SOXL, new BigDecimal("66.00"), new BigDecimal("35.00"));
    }
}
