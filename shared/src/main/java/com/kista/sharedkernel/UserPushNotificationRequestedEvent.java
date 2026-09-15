package com.kista.sharedkernel;

import java.util.UUID;

// trading-core가 FCM 발송을 root에 위임할 때 쓰는 이벤트 — fcm_device_tokens가 users FK라
// FCM 발송 자체는 root(FcmAdapter)가 계속 담당하고, trading-core는 이벤트만 던진다.
public record UserPushNotificationRequestedEvent(UUID userId, String title, String body) {
}
