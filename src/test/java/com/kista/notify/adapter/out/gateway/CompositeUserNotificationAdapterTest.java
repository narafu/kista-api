package com.kista.notify.adapter.out.gateway;

import com.kista.user.domain.model.User;
import com.kista.user.domain.model.NotificationChannel;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
