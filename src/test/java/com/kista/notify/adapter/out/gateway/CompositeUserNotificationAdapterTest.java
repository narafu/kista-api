package com.kista.notify.adapter.out.gateway;

import com.kista.user.application.event.NewUserRegisteredEvent;
import com.kista.user.application.event.UserApprovedEvent;
import com.kista.user.application.event.UserRejectedEvent;
import com.kista.user.application.event.UserReappliedEvent;
import com.kista.user.application.port.output.UserPort;
import com.kista.user.domain.model.User;
import com.kista.sharedkernel.NotificationChannel;
import com.kista.sharedkernel.UserRole;
import com.kista.sharedkernel.UserStatus;
import com.kista.support.DomainFixtures;
import static com.kista.support.DomainFixtures.recipientOf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompositeUserNotificationAdapterTest {

    @Mock TelegramUserNotificationAdapter telegram;
    @Mock FcmAdapter fcm;
    @Mock UserPort userPort;

    CompositeUserNotificationAdapter composite;

    @BeforeEach
    void setUp() {
        composite = new CompositeUserNotificationAdapter(telegram, fcm, userPort);
    }

    // 테스트용 User 생성 헬퍼
    static User userWith(NotificationChannel channel) {
        return DomainFixtures.activeUser(UUID.randomUUID(), channel);
    }

    @Test
    void notifyNewUser_alwaysGoesToTelegram() {
        // FCM 채널 사용자도 신규가입 알림은 Telegram (관리자 알림)
        User fcmUser = userWith(NotificationChannel.FCM);

        composite.notifyNewUser(recipientOf(fcmUser));

        verify(telegram).notifyNewUser(recipientOf(fcmUser));
        verify(fcm, never()).notifyNewUser(any());
    }



    @Test
    void notifyApproved_ignoresChannelSetting_alwaysCallsBothAdapters() {
        // 승인/거절은 notificationChannel 설정과 무관하게 연결된 수단 전부로 발송 — 각 어댑터가 자체 게이트 보유
        User user = userWith(NotificationChannel.TELEGRAM);

        composite.notifyApproved(recipientOf(user));

        verify(telegram).notifyApproved(recipientOf(user));
        verify(fcm).notifyApproved(recipientOf(user));
    }

    @Test
    void notifyRejected_ignoresChannelSetting_alwaysCallsBothAdapters() {
        User user = userWith(NotificationChannel.NONE);

        composite.notifyRejected(recipientOf(user));

        verify(telegram).notifyRejected(recipientOf(user));
        verify(fcm).notifyRejected(recipientOf(user));
    }

    @Test
    void onNewUserRegistered_pending_sendsApprovalRequest() {
        User user = DomainFixtures.userWithStatus(UUID.randomUUID(), UserStatus.PENDING);
        when(userPort.findByIdOrThrow(user.id())).thenReturn(user);

        composite.onNewUserRegistered(new NewUserRegisteredEvent(user.id()));

        verify(telegram).notifyNewUser(recipientOf(user));
        verify(telegram, never()).notifyAutoApprovedUser(any());
    }

    @Test
    void onNewUserRegistered_activeNonAdmin_sendsAutoApprovedInfo() {
        // 승인 불필요 설정으로 즉시 ACTIVE 등록된 일반 사용자 — 관리자에게 정보성 알림
        User user = DomainFixtures.userWithStatus(UUID.randomUUID(), UserStatus.ACTIVE, UserRole.USER);
        when(userPort.findByIdOrThrow(user.id())).thenReturn(user);

        composite.onNewUserRegistered(new NewUserRegisteredEvent(user.id()));

        verify(telegram).notifyAutoApprovedUser(recipientOf(user));
        verify(telegram, never()).notifyNewUser(any());
    }

    @Test
    void onNewUserRegistered_activeAdmin_skipsNotification() {
        // 관리자 seed 부트스트랩 — 알림 불필요
        User user = DomainFixtures.userWithStatus(UUID.randomUUID(), UserStatus.ACTIVE, UserRole.ADMIN);
        when(userPort.findByIdOrThrow(user.id())).thenReturn(user);

        composite.onNewUserRegistered(new NewUserRegisteredEvent(user.id()));

        verifyNoInteractions(telegram, fcm);
    }

    @Test
    void onNewUserRegistered_userNotFound_propagatesException() {
        UUID missingUserId = UUID.randomUUID();
        when(userPort.findByIdOrThrow(missingUserId))
                .thenThrow(new NoSuchElementException("사용자를 찾을 수 없습니다: " + missingUserId));

        assertThatThrownBy(() -> composite.onNewUserRegistered(new NewUserRegisteredEvent(missingUserId)))
                .isInstanceOf(NoSuchElementException.class);

        verifyNoInteractions(telegram, fcm);
    }

    @Test
    void onUserApproved_notifiesBothAdapters() {
        User user = userWith(NotificationChannel.TELEGRAM);
        when(userPort.findByIdOrThrow(user.id())).thenReturn(user);

        composite.onUserApproved(new UserApprovedEvent(user.id()));

        verify(telegram).notifyApproved(recipientOf(user));
        verify(fcm).notifyApproved(recipientOf(user));
    }

    @Test
    void onUserRejected_notifiesBothAdapters() {
        User user = userWith(NotificationChannel.NONE);
        when(userPort.findByIdOrThrow(user.id())).thenReturn(user);

        composite.onUserRejected(new UserRejectedEvent(user.id()));

        verify(telegram).notifyRejected(recipientOf(user));
        verify(fcm).notifyRejected(recipientOf(user));
    }

    @Test
    void onUserReapplied_sendsApprovalRequest() {
        User user = DomainFixtures.userWithStatus(UUID.randomUUID(), UserStatus.PENDING);
        when(userPort.findByIdOrThrow(user.id())).thenReturn(user);

        composite.onUserReapplied(new UserReappliedEvent(user.id()));

        verify(telegram).notifyNewUser(recipientOf(user));
    }
}
