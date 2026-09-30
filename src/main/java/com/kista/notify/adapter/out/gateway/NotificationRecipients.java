package com.kista.notify.adapter.out.gateway;

import com.kista.notify.domain.model.NotificationRecipient;
import com.kista.user.domain.model.User;

// User 애그리게이트 → notify 수신자 투영의 단일 진입점 — notify 도메인은 User를 모르므로 변환은 gateway 어댑터 경계에서만 한다
final class NotificationRecipients {

    private NotificationRecipients() {
    }

    static NotificationRecipient from(User user) {
        return new NotificationRecipient(user.id(), user.nickname(), user.notificationChannel(),
                user.telegramBotToken(), user.telegramChatId(), user.rejectReason());
    }
}
