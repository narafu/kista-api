package com.kista.notify.adapter.out.gateway;

import com.kista.finance.application.event.FinanceRegistrationReminderDueEvent;
import com.kista.notify.application.port.output.PushNotificationPort;
import com.kista.notify.domain.model.NotificationRecipient;
import com.kista.platform.telegram.TelegramHttpClient;
import com.kista.sharedkernel.NotificationType;
import com.kista.user.application.port.output.UserPort;
import com.kista.user.application.port.output.UserSettingsPort;
import com.kista.user.domain.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

// finance가 발행하는 가계부 등록 리마인더 이벤트를 구독해 사용자가 설정한 채널(텔레그램 봇·FCM)로 라우팅한다 —
// AlertNotifier가 market/benchmark 발행자 이벤트를 구독하는 것과 같은 패턴(발행 모듈은 notify를 모른다).
// 발행 지점이 트랜잭션 밖 스케쥴러라 @TransactionalEventListener가 아닌 동기 @EventListener로 받는다.
// 알림 설정 게이트(FINANCE_REMINDER 활성 여부)와 채널 라우팅은 여기서만 판단한다.
// 채널 leg는 각각 격리한다 — 텔레그램이 실패해도 FCM은 시도하고, 어느 쪽 예외도 발행자(finance)로 전파하지 않는다.
// 빈 이름을 명시하는 이유: finance.application.service의 같은 이름 클래스와 기본 빈 이름이 충돌한다.
@Slf4j
@Component
@RequiredArgsConstructor
class FinanceReminderChannelNotifier {

    private static final NotificationType TYPE = NotificationType.FINANCE_REMINDER; // 사용자 알림 설정 게이트 키

    private final UserPort userPort;                     // 수신자 조회(id → 채널·봇 연결 정보)
    private final UserSettingsPort userSettingsPort;     // 알림 유형별 on/off 설정
    private final TelegramHttpClient telegramHttpClient; // 사용자 봇으로 텔레그램 발송
    private final PushNotificationPort pushNotificationPort; // FCM 푸시(채널 포함 여부 판정 포함)

    @EventListener
    public void onReminderDue(FinanceRegistrationReminderDueEvent event) {
        // 1단계: 수신자 조회 — 탈퇴 등으로 없으면 skip
        Optional<User> user = userPort.findById(event.userId());
        if (user.isEmpty()) {
            log.warn("[userId={}] 알림 요청 수신자를 찾을 수 없어 건너뜀: {}", event.userId(), TYPE);
            return;
        }
        // 2단계: 사용자 알림 설정에서 해당 유형이 꺼져 있으면 skip
        if (!userSettingsPort.findOrDefault(event.userId()).isNotificationEnabled(TYPE)) return;

        // 3단계: 채널 라우팅 — Telegram/FCM 순서 고정, 각 leg 실패는 서로 격리
        NotificationRecipient recipient = NotificationRecipients.from(user.get());
        if (recipient.notificationChannel().includesTelegram() && recipient.hasTelegramBot()) {
            try {
                telegramHttpClient.sendMessage(recipient.telegramChatId(), event.body(), recipient.telegramBotToken());
            } catch (Exception e) {
                log.warn("[userId={}] 가계부 리마인더 텔레그램 발송 실패: {}", event.userId(), e.getMessage());
            }
        }
        try {
            pushNotificationPort.pushIfEnabled(event.userId(), event.title(), event.body());
        } catch (Exception e) {
            log.warn("[userId={}] 가계부 리마인더 FCM 발송 실패: {}", event.userId(), e.getMessage());
        }
    }
}
