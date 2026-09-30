package com.kista.notify.adapter.out.gateway;

import com.kista.notify.domain.model.NotificationRecipient;
import com.kista.platform.telegram.TelegramHttpClient;
import com.kista.sharedkernel.NotificationType;
import com.kista.sharedkernel.UserNotificationRequestedEvent;
import com.kista.user.application.port.output.UserPort;
import com.kista.user.application.port.output.UserSettingsPort;
import com.kista.user.domain.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

// 일반 사용자 알림 요청(UserNotificationRequestedEvent)을 사용자가 설정한 채널(텔레그램 봇·FCM)로 라우팅한다.
// 발행 지점이 트랜잭션 밖 스케쥴러라 @TransactionalEventListener가 아닌 동기 @EventListener로 받는다.
// 알림 설정 게이트(type 활성 여부)와 채널 라우팅은 여기서만 판단한다 — 발행 모듈은 대상 사용자와 문구만 넘긴다.
@Slf4j
@Component
@RequiredArgsConstructor
class UserNotificationRequestedListener {

    private final UserPort userPort;                     // 수신자 조회(id → 채널·봇 연결 정보)
    private final UserSettingsPort userSettingsPort;     // 알림 유형별 on/off 설정
    private final TelegramHttpClient telegramHttpClient; // 사용자 봇으로 텔레그램 발송
    private final FcmAdapter fcmAdapter;                 // FCM 푸시 발송

    @EventListener
    public void onUserNotificationRequested(UserNotificationRequestedEvent event) {
        // 1단계: 수신자 조회 — 탈퇴 등으로 없으면 skip
        Optional<User> user = userPort.findById(event.userId());
        if (user.isEmpty()) {
            log.warn("[userId={}] 알림 요청 수신자를 찾을 수 없어 건너뜀: {}", event.userId(), event.type());
            return;
        }
        // 2단계: 사용자 알림 설정에서 해당 유형이 꺼져 있으면 skip
        if (!userSettingsPort.findOrDefault(event.userId()).isNotificationEnabled(event.type())) return;

        // 3단계: 채널 라우팅 — Telegram/FCM 순서 고정 (notificationChannel 기준)
        NotificationRecipient recipient = NotificationRecipients.from(user.get());
        if (recipient.notificationChannel().includesTelegram() && recipient.hasTelegramBot()) {
            telegramHttpClient.sendMessage(recipient.telegramChatId(), telegramPrefix(event.type()) + event.body(),
                    recipient.telegramBotToken());
        }
        if (recipient.notificationChannel().includesFcm()) {
            fcmAdapter.send(recipient.userId(), event.title(), event.body());
        }
    }

    // 텔레그램 본문 앞 아이콘 — FCM은 title이 따로 있어 텔레그램에서만 유형 식별용으로 붙인다
    private static String telegramPrefix(NotificationType type) {
        return type == NotificationType.FINANCE_REMINDER ? "📒 " : "";
    }
}
