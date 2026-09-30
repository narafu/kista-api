package com.kista.notify.adapter.out.gateway;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.kista.notify.domain.model.NotificationRecipient;
import com.kista.notify.application.port.output.FcmDeviceTokenPort;
import com.kista.notify.application.port.output.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class FcmAdapter implements UserNotificationPort {

    private final FcmDeviceTokenPort fcmDeviceTokenPort;
    private final Optional<FirebaseMessaging> firebaseMessaging; // null-safe — 미설정 시 empty

    @Override
    public void notifyNewUser(NotificationRecipient user) {
        // 신규 가입 알림은 관리자 전용 — CompositeAdapter에서 항상 Telegram 경유
    }

    @Override
    public void notifyAutoApprovedUser(NotificationRecipient user) {
        // 신규 가입 알림은 관리자 전용 — CompositeAdapter에서 항상 Telegram 경유
    }

    @Override
    public void notifyApproved(NotificationRecipient user) {
        send(user.userId(), "KISTA 알림", "✅ 가입이 승인되었습니다.");
    }

    @Override
    public void notifyRejected(NotificationRecipient user) {
        send(user.userId(), "KISTA 알림", "❌ 가입이 거절되었습니다.");
    }

    // package-private — 외부 호출은 PushNotificationPort(UserPushNotificationAdapter)를 거쳐 채널 판정을 한 곳에서 한다
    void send(UUID userId, String title, String body) {
        if (firebaseMessaging.isEmpty()) {
            return;
        }
        List<String> tokens = fcmDeviceTokenPort.findTokensByUserId(userId);
        if (tokens.isEmpty()) {
            return;
        }
        MulticastMessage message = MulticastMessage.builder()
                .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                .addAllTokens(tokens)
                .build();
        try {
            var result = firebaseMessaging.get().sendEachForMulticast(message);
            // 등록 만료된 토큰 자동 삭제
            for (int i = 0; i < result.getResponses().size(); i++) {
                if (!result.getResponses().get(i).isSuccessful()) {
                    String failedToken = tokens.get(i);
                    log.warn("FCM 토큰 전송 실패, 삭제: {}", failedToken);
                    fcmDeviceTokenPort.delete(userId, failedToken);
                }
            }
        } catch (FirebaseMessagingException e) {
            log.error("FCM 전송 오류: {}", e.getMessage());
        }
    }
}
