package com.kista.api.user.application.service;

import com.kista.shared.kernel.NotificationChannel;
import com.kista.shared.kernel.UserNotifyProfileChangedEvent;
import com.kista.shared.kernel.UserStatus;
import com.kista.api.user.application.port.output.UserPort;
import com.kista.api.user.application.port.output.UserSettingsPort;
import com.kista.api.user.domain.model.User;
import com.kista.api.user.domain.model.UserSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

// trading-core의 user_notify_profile 캐시 동기화 이벤트 발행 SSOT.
// 프로필은 User.status(활성 여부) + UserSettings(알림·잔고검증)로 나뉘어 있어 어느 쪽이 바뀌든
// 나머지 절반을 조회해 온전한 스냅샷을 실어보낸다 — 방금 쓴 쪽은 인자로 받아 재조회하지 않는다.
@Component
@RequiredArgsConstructor
class UserNotifyProfilePublisher {

    private final UserPort userPort;
    private final UserSettingsPort userSettingsPort;
    private final ApplicationEventPublisher eventPublisher;

    // 상태(가입·승인·거절·재신청·ADMIN 승격)·텔레그램·알림 채널이 바뀐 직후 — 설정은 조회해서 채운다
    void publishStatusChanged(User user) {
        publish(user.id(), userSettingsPort.findOrDefault(user.id()), user.status() == UserStatus.ACTIVE,
                user.telegramBotToken(), user.telegramChatId(), user.notificationChannel());
    }

    // 알림·잔고검증 설정이 바뀐 직후 — 상태·텔레그램은 조회해서 채운다(소프트 삭제된 사용자는 비활성 취급)
    void publishSettingsChanged(UserSettings settings) {
        User user = userPort.findById(settings.userId()).orElse(null);
        boolean active = user != null && user.status() == UserStatus.ACTIVE;
        publish(settings.userId(), settings, active,
                user == null ? null : user.telegramBotToken(),
                user == null ? null : user.telegramChatId(),
                user == null ? null : user.notificationChannel());
    }

    private void publish(UUID userId, UserSettings settings, boolean active, String telegramBotToken, String chatId,
                         NotificationChannel channel) {
        eventPublisher.publishEvent(new UserNotifyProfileChangedEvent(
                userId, settings.notificationPrefs(), active,
                telegramBotToken, chatId, channel, Instant.now())); // 스냅샷 시각 — trading-core가 재시도된 옛 스냅샷을 거르는 버전
    }
}
