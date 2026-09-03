package com.kista.user.application.service;

import com.kista.user.domain.model.User;
import com.kista.sharedkernel.NotificationChannel;
import com.kista.user.application.usecase.UserProfileUseCase;
import com.kista.user.application.port.output.TelegramBotInfoPort;
import com.kista.user.application.port.output.UserPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class UserProfileService implements UserProfileUseCase {

    private final UserPort userPort;
    private final TelegramBotInfoPort telegramBotInfoPort; // 봇 토큰 검증 + username 취득

    @Override
    public void updateTelegram(UUID userId, String botToken, String chatId) {
        // botToken 유효성 검증 + username 취득 (실패 시 IllegalArgumentException)
        String botUsername = telegramBotInfoPort.getUsername(botToken);
        User user = userPort.findByIdOrThrow(userId);
        userPort.save(user.withTelegram(botToken, chatId, botUsername));
        log.info("텔레그램 설정 업데이트: userId={}, botUsername={}", userId, botUsername);
    }

    @Override
    public void removeTelegram(UUID userId) {
        User user = userPort.findByIdOrThrow(userId);
        userPort.save(user.withTelegram(null, null, null));
        log.info("텔레그램 설정 해제: userId={}", userId);
    }

    @Override
    public void updateNotificationChannel(UUID userId, NotificationChannel channel) {
        User user = userPort.findByIdOrThrow(userId);
        userPort.save(user.withNotificationChannel(channel));
        log.info("알림 채널 변경: userId={}, channel={}", userId, channel);
    }

    @Override
    public void updateNickname(UUID userId, String nickname) {
        User user = userPort.findByIdOrThrow(userId);
        userPort.save(user.withNickname(nickname.strip()));
        log.info("닉네임 변경: userId={}", userId);
    }
}
