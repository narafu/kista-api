package com.kista.notify.adapter.out.gateway;

import com.kista.user.domain.model.User;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramUserNotificationAdapterTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    RestClient restClient;

    TelegramUserNotificationAdapter adapter;

    static final TelegramProperties PROPS = new TelegramProperties("admin-token", "admin-chat");

    @BeforeEach
    void setUp() {
        TelegramHttpClient httpClient = new TelegramHttpClient(restClient);
        adapter = new TelegramUserNotificationAdapter(httpClient, PROPS);
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyRejected_withReason_appendsReasonToMessage() {
        User user = DomainFixtures.telegramUser(UUID.randomUUID(), "user-bot-token", "user-chat-789")
                .withRejection("서류 미비");
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);

        adapter.notifyRejected(user);

        verify(restClient.post().uri(anyString())).body(bodyCaptor.capture());
        String text = ((Map<String, String>) bodyCaptor.getValue()).get("text");
        assertThat(text).isEqualTo("❌ 가입 신청이 거절되었습니다.\n사유: 서류 미비");
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyRejected_withNullReason_sendsUnchangedMessage() {
        User user = DomainFixtures.telegramUser(UUID.randomUUID(), "user-bot-token", "user-chat-789")
                .withRejection(null);
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);

        adapter.notifyRejected(user);

        verify(restClient.post().uri(anyString())).body(bodyCaptor.capture());
        String text = ((Map<String, String>) bodyCaptor.getValue()).get("text");
        assertThat(text).isEqualTo("❌ 가입 신청이 거절되었습니다.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyRejected_withBlankReason_sendsUnchangedMessage() {
        // UserService.reject()가 blank -> null로 정규화하지만, 어댑터 자체 방어 로직(isBlank 가드)을 직접 검증
        User user = DomainFixtures.telegramUser(UUID.randomUUID(), "user-bot-token", "user-chat-789")
                .withRejection("   ");
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);

        adapter.notifyRejected(user);

        verify(restClient.post().uri(anyString())).body(bodyCaptor.capture());
        String text = ((Map<String, String>) bodyCaptor.getValue()).get("text");
        assertThat(text).isEqualTo("❌ 가입 신청이 거절되었습니다.");
    }
}
