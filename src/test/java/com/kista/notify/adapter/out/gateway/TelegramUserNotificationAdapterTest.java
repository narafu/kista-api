package com.kista.notify.adapter.out.gateway;

import com.kista.platform.telegram.TelegramHttpClient;
import com.kista.platform.telegram.TelegramProperties;
import com.kista.user.domain.model.User;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

// HTTP 전송 상세는 platform TelegramHttpClientTest가 검증 — 여기선 사용자 봇 토큰·Chat ID와 문구만 확인한다
@ExtendWith(MockitoExtension.class)
class TelegramUserNotificationAdapterTest {

    @Mock TelegramHttpClient telegramHttpClient;

    TelegramUserNotificationAdapter adapter;

    static final TelegramProperties PROPS = new TelegramProperties("admin-token", "admin-chat");

    @BeforeEach
    void setUp() {
        adapter = new TelegramUserNotificationAdapter(telegramHttpClient, PROPS);
    }

    @Test
    void notifyRejected_withReason_appendsReasonToMessage() {
        User user = DomainFixtures.telegramUser(UUID.randomUUID(), "user-bot-token", "user-chat-789")
                .withRejection("서류 미비");

        adapter.notifyRejected(DomainFixtures.recipientOf(user));

        verify(telegramHttpClient).sendMessage("user-chat-789", "❌ 가입 신청이 거절되었습니다.\n사유: 서류 미비", "user-bot-token");
    }

    @Test
    void notifyRejected_withNullReason_sendsUnchangedMessage() {
        User user = DomainFixtures.telegramUser(UUID.randomUUID(), "user-bot-token", "user-chat-789")
                .withRejection(null);

        adapter.notifyRejected(DomainFixtures.recipientOf(user));

        verify(telegramHttpClient).sendMessage("user-chat-789", "❌ 가입 신청이 거절되었습니다.", "user-bot-token");
    }

    @Test
    void notifyRejected_withBlankReason_sendsUnchangedMessage() {
        // UserService.reject()가 blank -> null로 정규화하지만, 어댑터 자체 방어 로직(isBlank 가드)을 직접 검증
        User user = DomainFixtures.telegramUser(UUID.randomUUID(), "user-bot-token", "user-chat-789")
                .withRejection("   ");

        adapter.notifyRejected(DomainFixtures.recipientOf(user));

        verify(telegramHttpClient).sendMessage("user-chat-789", "❌ 가입 신청이 거절되었습니다.", "user-bot-token");
    }
}
