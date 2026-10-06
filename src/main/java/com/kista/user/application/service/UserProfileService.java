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
    private final UserNotifyProfilePublisher userNotifyProfilePublisher; // trading-core 복제본 동기화(텔레그램 포함)

    @Override
    public void updateTelegram(UUID userId, String botToken, String chatId) {
        // botToken 유효성 검증 + username 취득 (실패 시 IllegalArgumentException)
        String botUsername = telegramBotInfoPort.getUsername(botToken);
        User user = userPort.findByIdOrThrow(userId);
        // 봇을 연결하면 알림 채널에 텔레그램을 더한다 — 채널을 따로 고르지 않아도 매매 리포트가 텔레그램으로 온다
        User saved = userPort.save(user.withTelegram(botToken, chatId, botUsername)
                .withNotificationChannel(user.notificationChannel().withTelegram()));
        userNotifyProfilePublisher.publishStatusChanged(saved);
        log.info("텔레그램 설정 업데이트: userId={}, botUsername={}", userId, botUsername);
    }

    @Override
    public void removeTelegram(UUID userId) {
        User user = userPort.findByIdOrThrow(userId);
        // 봇을 해제하면 알림 채널에서 텔레그램을 뺀다 — 연결 없는 텔레그램 채널이 남지 않도록
        User saved = userPort.save(user.withTelegram(null, null, null)
                .withNotificationChannel(user.notificationChannel().withoutTelegram()));
        userNotifyProfilePublisher.publishStatusChanged(saved);
        log.info("텔레그램 설정 해제: userId={}", userId);
    }

    @Override
    public void updateNotificationChannel(UUID userId, NotificationChannel channel) {
        User user = userPort.findByIdOrThrow(userId);
        User saved = userPort.save(user.withNotificationChannel(channel));
        // kista-trading 매매 알림도 채널을 따르므로 복제본에 전달
        userNotifyProfilePublisher.publishStatusChanged(saved);
        log.info("알림 채널 변경: userId={}, channel={}", userId, channel);
    }

    @Override
    public void updateNickname(UUID userId, String nickname) {
        User user = userPort.findByIdOrThrow(userId);
        userPort.save(user.withNickname(nickname.strip()));
        log.info("닉네임 변경: userId={}", userId);
    }
}
