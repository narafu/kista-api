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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompositeUserNotificationAdapterTest {

    @Mock TelegramUserNotificationAdapter telegram;
    @Mock FcmAdapter fcm;

    CompositeUserNotificationAdapter composite;

    @BeforeEach
    void setUp() {
        composite = new CompositeUserNotificationAdapter(telegram, fcm);
    }

    // 테스트용 User 생성 헬퍼
    static User userWith(NotificationChannel channel) {
        return DomainFixtures.activeUser(UUID.randomUUID(), channel);
    }

    // 테스트용 TradingReport 생성 헬퍼
    static TradingReport report() {
        return new TradingReport(LocalDate.now(), Strategy.Type.INFINITE, Strategy.Ticker.SOXL, new BigDecimal("500.00"), new BigDecimal("200.00"));
    }

    @Test
    void telegramChannel_routesToTelegramOnly() {
        User user = userWith(NotificationChannel.TELEGRAM);
        Account account = mock(Account.class);
        TradingReport r = report();

        composite.notifyTradingReport(user, account, r);

        verify(telegram).notifyTradingReport(user, account, r);
        verify(fcm, never()).notifyTradingReport(any(), any(), any());
    }

    @Test
    void fcmChannel_routesToFcmOnly() {
        User user = userWith(NotificationChannel.FCM);
        Account account = mock(Account.class);
        TradingReport r = report();

        composite.notifyTradingReport(user, account, r);

        verify(fcm).notifyTradingReport(user, account, r);
        verify(telegram, never()).notifyTradingReport(any(), any(), any());
    }

    @Test
    void allChannel_routesToBoth() {
        User user = userWith(NotificationChannel.ALL);
        Account account = mock(Account.class);
        TradingReport r = report();

        composite.notifyTradingReport(user, account, r);

        verify(telegram).notifyTradingReport(user, account, r);
        verify(fcm).notifyTradingReport(user, account, r);
    }

    @Test
    void notifyNewUser_alwaysGoesToTelegram() {
        // FCM 채널 사용자도 신규가입 알림은 Telegram (관리자 알림)
        User fcmUser = userWith(NotificationChannel.FCM);

        composite.notifyNewUser(fcmUser);

        verify(telegram).notifyNewUser(fcmUser);
        verify(fcm, never()).notifyNewUser(any());
    }



    @Test
    void notifyApproved_telegramChannel_routesToTelegramOnly() {
        User user = userWith(NotificationChannel.TELEGRAM);

        composite.notifyApproved(user);

        verify(telegram).notifyApproved(user);
        verify(fcm, never()).notifyApproved(any());
    }

    @Test
    void notifyRejected_allChannel_routesToBoth() {
        User user = userWith(NotificationChannel.ALL);

        composite.notifyRejected(user);

        verify(telegram).notifyRejected(user);
        verify(fcm).notifyRejected(user);
    }
}
