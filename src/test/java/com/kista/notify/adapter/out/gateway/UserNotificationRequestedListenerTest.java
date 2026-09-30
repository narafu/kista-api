package com.kista.notify.adapter.out.gateway;

import com.kista.platform.telegram.TelegramHttpClient;
import com.kista.sharedkernel.NotificationChannel;
import com.kista.sharedkernel.NotificationType;
import com.kista.sharedkernel.UserNotificationRequestedEvent;
import com.kista.support.DomainFixtures;
import com.kista.user.application.port.output.UserPort;
import com.kista.user.application.port.output.UserSettingsPort;
import com.kista.user.domain.model.User;
import com.kista.user.domain.model.UserSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserNotificationRequestedListenerTest {

    static final String TITLE = "가계부 등록을 아직 안 하셨어요";
    static final String BODY = "9월 가계부(자산·수입·소비·저축) 등록이 아직 없어요. 지금 등록해보세요.";

    @Mock UserPort userPort;
    @Mock UserSettingsPort userSettingsPort;
    @Mock TelegramHttpClient telegramHttpClient;
    @Mock FcmAdapter fcmAdapter;

    private UserNotificationRequestedEvent eventFor(User user) {
        return new UserNotificationRequestedEvent(user.id(), NotificationType.FINANCE_REMINDER, TITLE, BODY);
    }

    private UserNotificationRequestedListener listener() {
        return new UserNotificationRequestedListener(userPort, userSettingsPort, telegramHttpClient, fcmAdapter);
    }

    // 텔레그램 봇이 연결된 사용자 — 채널 라우팅만 케이스별로 달라진다
    private static User linkedUser(NotificationChannel channel) {
        return DomainFixtures.activeUser(UUID.randomUUID(), channel).withTelegram("bot-token", "chat-1", null);
    }

    private User stub(NotificationChannel channel) {
        User user = linkedUser(channel);
        when(userPort.findById(user.id())).thenReturn(Optional.of(user));
        when(userSettingsPort.findOrDefault(user.id())).thenReturn(UserSettings.defaultFor(user.id()));
        return user;
    }

    @Test
    void 사용자가_없으면_아무것도_보내지_않는다() {
        UUID userId = UUID.randomUUID();
        when(userPort.findById(userId)).thenReturn(Optional.empty());

        listener().onUserNotificationRequested(
                new UserNotificationRequestedEvent(userId, NotificationType.FINANCE_REMINDER, TITLE, BODY));

        verifyNoInteractions(userSettingsPort, telegramHttpClient, fcmAdapter);
    }

    @Test
    void 알림_설정이_꺼져_있으면_건너뛴다() {
        User user = linkedUser(NotificationChannel.ALL);
        when(userPort.findById(user.id())).thenReturn(Optional.of(user));
        when(userSettingsPort.findOrDefault(user.id())).thenReturn(
                UserSettings.defaultFor(user.id()).withNotificationPrefs(Map.of(NotificationType.FINANCE_REMINDER, false)));

        listener().onUserNotificationRequested(eventFor(user));

        verifyNoInteractions(telegramHttpClient, fcmAdapter);
    }

    @Test
    void 텔레그램_채널이면_기존_문구로_봇에_보낸다() {
        User user = stub(NotificationChannel.TELEGRAM);

        listener().onUserNotificationRequested(eventFor(user));

        verify(telegramHttpClient).sendMessage(user.telegramChatId(), "📒 " + BODY, user.telegramBotToken());
        verify(fcmAdapter, never()).send(any(), any(), any());
    }

    @Test
    void FCM_채널이면_제목과_본문으로_푸시한다() {
        User user = stub(NotificationChannel.FCM);

        listener().onUserNotificationRequested(eventFor(user));

        verify(fcmAdapter).send(user.id(), TITLE, BODY);
        verifyNoInteractions(telegramHttpClient);
    }

    @Test
    void ALL_채널이면_텔레그램과_FCM_모두_보낸다() {
        User user = stub(NotificationChannel.ALL);

        listener().onUserNotificationRequested(eventFor(user));

        verify(telegramHttpClient).sendMessage(user.telegramChatId(), "📒 " + BODY, user.telegramBotToken());
        verify(fcmAdapter).send(user.id(), TITLE, BODY);
    }

    @Test
    void NONE_채널이면_어디에도_보내지_않는다() {
        User user = stub(NotificationChannel.NONE);

        listener().onUserNotificationRequested(eventFor(user));

        verifyNoInteractions(telegramHttpClient, fcmAdapter);
    }
}
