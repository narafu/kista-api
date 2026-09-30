package com.kista.notify.application.port.output;

import java.util.UUID;

public interface PushNotificationPort {

    // 사용자의 notificationChannel이 FCM을 포함할 때만 푸시한다.
    // 사용자가 없으면 조용히 skip한다(탈퇴 직후 도착한 체결 푸시 등 정상 경로)
    void pushIfEnabled(UUID userId, String title, String body);
}
