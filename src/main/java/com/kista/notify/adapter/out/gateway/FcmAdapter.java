package com.kista.notify.adapter.out.gateway;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.kista.user.domain.model.User;
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
    public void notifyNewUser(User user) {
        // 신규 가입 알림은 관리자 전용 — CompositeAdapter에서 항상 Telegram 경유
    }

    @Override
    public void notifyAutoApprovedUser(User user) {
        // 신규 가입 알림은 관리자 전용 — CompositeAdapter에서 항상 Telegram 경유
    }

    @Override
    public void notifyApproved(User user) {
        send(user.id(), "KISTA 알림", "✅ 가입이 승인되었습니다.");
    }

    @Override
    public void notifyRejected(User user) {
        send(user.id(), "KISTA 알림", "❌ 가입이 거절되었습니다.");
    }

    @Override
    public void notifyFinanceRegistrationReminder(User user, String month) {
        send(user.id(), "가계부 등록을 아직 안 하셨어요",
                month + " 가계부(자산·수입·소비·저축) 등록이 아직 없어요. 지금 등록해보세요.");
    }

    // package-private — PushNotificationRelayListener(같은 패키지)가 trading-core 위임 발송에 재사용
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
