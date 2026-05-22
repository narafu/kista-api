package com.kista.domain.model.user;

public enum NotificationChannel {
    TELEGRAM,   // 텔레그램 봇 알림
    FCM,        // Firebase Cloud Messaging 푸시
    ALL         // 텔레그램 + FCM 동시 발송
}
