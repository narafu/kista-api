package com.kista.notify.adapter.out.gateway;

import com.kista.notify.application.port.output.PushNotificationPort;
import com.kista.platform.telegram.TelegramHttpClient;
import com.kista.sharedkernel.NotificationChannel;
import com.kista.sharedkernel.NotificationType;
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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserChannelNotifierTest {

    static final NotificationType TYPE = NotificationType.FINANCE_REMINDER;
    static final String TITLE = "가계부 등록을 아직 안 하셨어요";
    static final String BODY = "📒 9월 가계부(자산·수입·소비·저축) 등록이 아직 없어요. 지금 등록해보세요.";

    @Mock UserPort userPort;
    @Mock UserSettingsPort userSettingsPort;
    @Mock TelegramHttpClient telegramHttpClient;
    @Mock PushNotificationPort pushNotificationPort;

    private UserChannelNotifier notifier() {
        return new UserChannelNotifier(userPort, userSettingsPort, telegramHttpClient, pushNotificationPort);
    }

    private void send(User user) {
        notifier().notify(user.id(), TYPE, TITLE, BODY);
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

        notifier().notify(userId, TYPE, TITLE, BODY);

        verifyNoInteractions(userSettingsPort, telegramHttpClient, pushNotificationPort);
    }

    @Test
    void 알림_설정이_꺼져_있으면_건너뛴다() {
        User user = linkedUser(NotificationChannel.ALL);
        when(userPort.findById(user.id())).thenReturn(Optional.of(user));
        when(userSettingsPort.findOrDefault(user.id())).thenReturn(
                UserSettings.defaultFor(user.id()).withNotificationPrefs(Map.of(TYPE, false)));

        send(user);

        verifyNoInteractions(telegramHttpClient, pushNotificationPort);
    }

    @Test
    void 텔레그램_채널이면_본문_그대로_봇에_보낸다() {
        User user = stub(NotificationChannel.TELEGRAM);

        send(user);

        verify(telegramHttpClient).sendMessage(user.telegramChatId(), BODY, user.telegramBotToken());
    }

    @Test
    void 푸시는_채널_판정을_포트에_위임한다() {
        User user = stub(NotificationChannel.FCM);

        send(user);

        verify(pushNotificationPort).pushIfEnabled(user.id(), TITLE, BODY);
        verifyNoInteractions(telegramHttpClient);
    }

    @Test
    void ALL_채널이면_텔레그램과_푸시_모두_보낸다() {
        User user = stub(NotificationChannel.ALL);

        send(user);

        verify(telegramHttpClient).sendMessage(user.telegramChatId(), BODY, user.telegramBotToken());
        verify(pushNotificationPort).pushIfEnabled(user.id(), TITLE, BODY);
    }

    @Test
    void 텔레그램이_실패해도_푸시는_시도하고_예외를_전파하지_않는다() {
        User user = stub(NotificationChannel.ALL);
        doThrow(new IllegalStateException("telegram down"))
                .when(telegramHttpClient).sendMessage(any(), any(), any());

        assertThatCode(() -> send(user)).doesNotThrowAnyException();

        verify(pushNotificationPort).pushIfEnabled(user.id(), TITLE, BODY);
    }

    @Test
    void 푸시가_실패해도_예외를_전파하지_않는다() {
        User user = stub(NotificationChannel.ALL);
        doThrow(new IllegalStateException("fcm down")).when(pushNotificationPort).pushIfEnabled(any(), any(), any());

        assertThatCode(() -> send(user)).doesNotThrowAnyException();

        verify(telegramHttpClient).sendMessage(user.telegramChatId(), BODY, user.telegramBotToken());
    }

    @Test
    void NONE_채널이면_텔레그램은_보내지_않는다() {
        User user = stub(NotificationChannel.NONE);

        send(user);

        verify(telegramHttpClient, never()).sendMessage(any(), any(), any());
    }
}
