package com.kista.sharedkernel;

import java.util.UUID;

// 사용자가 설정한 채널(텔레그램 봇·FCM)로 라우팅되는 일반 사용자 알림 요청 — 같은 프로세스(root) 안에서 발행·구독한다.
// type은 사용자 알림 설정(UserSettings.notificationPrefs)의 게이트 키라 구독자(notify)가 발송 전 활성 여부를 확인한다.
// UserPushNotificationRequestedEvent(trading-core→root 크로스프로세스, FCM 전용 Redis Stream 전달)와 역할이 다르다.
public record UserNotificationRequestedEvent(UUID userId, NotificationType type, String title, String body) {
}
