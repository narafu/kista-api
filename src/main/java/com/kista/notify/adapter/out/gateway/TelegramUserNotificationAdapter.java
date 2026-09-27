package com.kista.notify.adapter.out.gateway;

import com.kista.user.domain.model.User;
import com.kista.notify.application.port.output.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
class TelegramUserNotificationAdapter implements UserNotificationPort {

    private final TelegramHttpClient telegramHttpClient; // 공통 HTTP 전송 유틸
    private final TelegramProperties props;              // 관리자 봇 설정

    @Override
    public void notifyNewUser(User user) {
        // 관리자에게 신규 가입 알림 + [승인]/[거절] 인라인 버튼
        String text = String.format("🆕 <b>신규 가입 신청</b>%n닉네임: %s%nUID: %s",
                user.nickname(), user.id());
        telegramHttpClient.sendWithInlineKeyboard(props.chatId(), text, props.botToken(),
                List.of(
                        Map.of("text", "✅ 승인", "callback_data", "approve:" + user.id()),
                        Map.of("text", "❌ 거절", "callback_data", "reject:" + user.id())
                ));
    }

    @Override
    public void notifyAutoApprovedUser(User user) {
        // 승인 불필요 설정으로 즉시 활성화된 신규 가입 — 관리자 조치 불필요라 버튼 없이 정보만 전달
        String text = String.format("🆕 <b>신규 가입 (자동 승인)</b>%n닉네임: %s%nUID: %s",
                user.nickname(), user.id());
        telegramHttpClient.sendMessage(props.chatId(), text, props.botToken());
    }

    @Override
    public void notifyApproved(User user) {
        sendIfLinked(user, "✅ 가입이 승인되었습니다.");
    }

    @Override
    public void notifyRejected(User user) {
        String text = "❌ 가입 신청이 거절되었습니다.";
        if (user.rejectReason() != null && !user.rejectReason().isBlank()) {
            text += String.format("\n사유: %s", user.rejectReason());
        }
        sendIfLinked(user, text);
    }

    @Override
    public void notifyFinanceRegistrationReminder(User user, String month) {
        sendIfLinked(user, String.format("📒 %s 가계부(자산·수입·소비·저축) 등록이 아직 없어요. 지금 등록해보세요.", month));
    }

    // 사용자 봇 연결 시에만 발송 — 미연결 시 조용히 skip
    private void sendIfLinked(User user, String text) {
        if (!user.hasTelegramBot()) return;
        telegramHttpClient.sendMessage(user.telegramChatId(), text, user.telegramBotToken());
    }
}
