package com.kista.user.application.service;

import com.kista.user.domain.model.User;
import com.kista.user.application.port.output.TelegramBotInfoPort;
import com.kista.user.application.port.output.UserPort;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.kista.sharedkernel.NotificationChannel;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserProfileService 단위 테스트")
class UserProfileServiceTest {

    @Mock UserPort userPort;
    @Mock TelegramBotInfoPort telegramBotInfoPort;

    @InjectMocks UserProfileService userProfileService;

    private User user(UUID id) {
        return DomainFixtures.activeUser(id, NotificationChannel.NONE);
    }

    @Test
    @DisplayName("텔레그램 설정 시 봇 username 검증 후 저장")
    void updateTelegram_validatesAndSaves() {
        UUID userId = UUID.randomUUID();
        when(telegramBotInfoPort.getUsername("bot-token")).thenReturn("kista_bot");
        when(userPort.findByIdOrThrow(userId)).thenReturn(user(userId));
        when(userPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userProfileService.updateTelegram(userId, "bot-token", "chat-1");

        verify(userPort).save(argThat(u -> "bot-token".equals(u.telegramBotToken())
                && "chat-1".equals(u.telegramChatId())));
    }

    @Test
    @DisplayName("텔레그램 해제 시 봇 토큰/채팅 ID null 저장")
    void removeTelegram_clears() {
        UUID userId = UUID.randomUUID();
        when(userPort.findByIdOrThrow(userId)).thenReturn(user(userId));
        when(userPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userProfileService.removeTelegram(userId);

        verify(userPort).save(argThat(u -> u.telegramBotToken() == null && u.telegramChatId() == null));
    }

    @Test
    @DisplayName("알림 채널 변경 시 저장")
    void updateNotificationChannel_saves() {
        UUID userId = UUID.randomUUID();
        when(userPort.findByIdOrThrow(userId)).thenReturn(user(userId));
        when(userPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userProfileService.updateNotificationChannel(userId, NotificationChannel.FCM);

        verify(userPort).save(argThat(u -> u.notificationChannel() == NotificationChannel.FCM));
    }

    @Test
    @DisplayName("닉네임 변경 시 strip 후 저장")
    void updateNickname_stripsAndSaves() {
        UUID userId = UUID.randomUUID();
        when(userPort.findByIdOrThrow(userId)).thenReturn(user(userId));
        when(userPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userProfileService.updateNickname(userId, "  새닉네임  ");

        verify(userPort).save(argThat(u -> "새닉네임".equals(u.nickname())));
    }
}
