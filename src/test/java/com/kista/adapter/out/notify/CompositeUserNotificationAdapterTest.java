package com.kista.adapter.out.notify;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        return new User(
                UUID.randomUUID(), "kakaoId", "nickname",
                User.UserStatus.ACTIVE, User.UserRole.USER,
                null, null, null,
                null,
                channel);
    }

    // 테스트용 TradingReport 생성 헬퍼
    static TradingReport report() {
        TradingSnapshot snapshot = new TradingSnapshot(
                10,                                  // holdings
                new BigDecimal("100.00"),            // averagePrice
                new BigDecimal("0.1500"),            // priceOffsetRate
                new BigDecimal("115.00"));           // targetPrice
        return new TradingReport(
                LocalDate.now(),
                snapshot,
                List.<Order>of(),                    // mainOrders
                List.<Order>of(),                    // correctionOrders
                new BigDecimal("500.00"),            // totalBoughtUsd
                new BigDecimal("200.00"));           // totalSoldUsd
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
    void notifyStrategyChanged_alwaysGoesToTelegram() {
        // 전략 변경 알림도 관리자 알림 — 채널 무관
        User user = userWith(NotificationChannel.FCM);
        Account account = mock(Account.class);
        TradingCycle cycle = mock(TradingCycle.class);

        composite.notifyStrategyChanged(user, account, cycle, "등록");

        verify(telegram).notifyStrategyChanged(eq(user), eq(account), eq(cycle), eq("등록"));
        verify(fcm, never()).notifyStrategyChanged(any(), any(), any(), any());
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
