package com.kista.notify.adapter.out.gateway;

import com.kista.user.domain.model.User;
import com.kista.notify.application.port.output.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Primary
@Component
@RequiredArgsConstructor
public class CompositeUserNotificationAdapter implements UserNotificationPort {

    private final TelegramUserNotificationAdapter telegram; // 인라인 버튼 지원 — 관리자 알림 전용
    private final FcmAdapter fcm;                           // FCM 푸시 — 사용자 채널 라우팅

    // 관리자 알림 — 채널 무관, 항상 Telegram (인라인 버튼 필요)
    @Override
    public void notifyNewUser(User user) {
        telegram.notifyNewUser(user);
    }

    @Override
    public void notifyAutoApprovedUser(User user) {
        telegram.notifyAutoApprovedUser(user);
    }

    // 사용자 알림 — notificationChannel에 따라 라우팅
    @Override public void notifyApproved(User user)                                     { route(user, p -> p.notifyApproved(user)); }
    @Override public void notifyRejected(User user)                                     { route(user, p -> p.notifyRejected(user)); }
    @Override public void notifyFinanceRegistrationReminder(User user, String month)    { route(user, p -> p.notifyFinanceRegistrationReminder(user, month)); }

    // notificationChannel 기반 어댑터 라우팅 — Telegram/FCM 순서 고정
    private void route(User user, Consumer<UserNotificationPort> action) {
        if (user.notificationChannel().includesTelegram()) action.accept(telegram);
        if (user.notificationChannel().includesFcm())      action.accept(fcm);
    }
}
