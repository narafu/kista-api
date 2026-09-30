package com.kista.notify.adapter.out.gateway;

import com.kista.notify.application.port.output.PushNotificationPort;
import com.kista.notify.domain.model.NotificationRecipient;
import com.kista.platform.telegram.TelegramHttpClient;
import com.kista.sharedkernel.NotificationType;
import com.kista.user.application.port.output.UserPort;
import com.kista.user.application.port.output.UserSettingsPort;
import com.kista.user.domain.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

// 사용자 대상 알림의 공용 채널 라우팅 — 알림 유형과 무관하게 "수신자 조회 → 유형별 설정 게이트 → 텔레그램(사용자 봇)·FCM 발송"을 담당한다.
// 발행자 이벤트 리스너(예: FinanceReminderChannelNotifier)는 이벤트 → (유형, 제목, 본문) 매핑만 하고 이 컴포넌트에 위임한다.
// 채널 leg는 각각 격리한다 — 텔레그램이 실패해도 FCM은 시도하고, 어느 쪽 예외도 호출자(발행자)로 전파하지 않는다.
@Slf4j
@Component
@RequiredArgsConstructor
class UserChannelNotifier {

    private final UserPort userPort;                     // 수신자 조회(id → 채널·봇 연결 정보)
    private final UserSettingsPort userSettingsPort;     // 알림 유형별 on/off 설정
    private final TelegramHttpClient telegramHttpClient; // 사용자 봇으로 텔레그램 발송
    private final PushNotificationPort pushNotificationPort; // FCM 푸시(채널 포함 여부 판정 포함)

    void notify(UUID userId, NotificationType type, String title, String body) {
        // 1단계: 수신자 조회 — 탈퇴 등으로 없으면 skip
        Optional<User> user = userPort.findById(userId);
        if (user.isEmpty()) {
            log.warn("[userId={}] 알림 요청 수신자를 찾을 수 없어 건너뜀: {}", userId, type);
            return;
        }
        // 2단계: 사용자 알림 설정에서 해당 유형이 꺼져 있으면 skip
        if (!userSettingsPort.findOrDefault(userId).isNotificationEnabled(type)) return;

        // 3단계: 채널 라우팅 — Telegram/FCM 순서 고정, 각 leg 실패는 서로 격리
        NotificationRecipient recipient = NotificationRecipients.from(user.get());
        if (recipient.notificationChannel().includesTelegram() && recipient.hasTelegramBot()) {
            try {
                telegramHttpClient.sendMessage(recipient.telegramChatId(), body, recipient.telegramBotToken());
            } catch (Exception e) {
                log.warn("[userId={}] {} 텔레그램 발송 실패: {}", userId, type, e.getMessage());
            }
        }
        try {
            pushNotificationPort.pushIfEnabled(userId, title, body);
        } catch (Exception e) {
            log.warn("[userId={}] {} FCM 발송 실패: {}", userId, type, e.getMessage());
        }
    }
}
