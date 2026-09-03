package com.kista.notify.adapter.out.gateway;

import com.kista.application.event.NewUserRegisteredEvent;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.trading.domain.model.TradingReport;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    void notifyTradingReport_withUserBot_sendsToUserChatId() {
        User user = DomainFixtures.telegramUser(UUID.randomUUID(), "user-bot-token", "user-chat-789");
        Account account = mock(Account.class);
        when(account.nickname()).thenReturn("SOXL계좌");

        adapter.notifyTradingReport(user, account, buildTestReport());

        verify(restClient.post()).uri(contains("/botuser-bot-token/sendMessage"));
    }

    @Test
    void notifyTradingReport_noUserBot_skips() {
        User user = DomainFixtures.activeUser(UUID.randomUUID(), NotificationChannel.TELEGRAM);
        Account account = mock(Account.class);

        adapter.notifyTradingReport(user, account, buildTestReport());

        verifyNoInteractions(restClient);
    }

    @Test
    void notifyBatchInterrupted_withUserBot_sendsToUserChatId() {
        User user = DomainFixtures.telegramUser(UUID.randomUUID(), "user-bot-token", "user-chat-789");
        Account account = mock(Account.class);
        when(account.nickname()).thenReturn("SOXL계좌");

        adapter.notifyBatchInterrupted(user, account);

        verify(restClient.post()).uri(contains("/botuser-bot-token/sendMessage"));
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

    @Test
    void onNewUserRegistered_pending_sendsApprovalRequestWithButtons() {
        User user = DomainFixtures.userWithStatus(UUID.randomUUID(), User.UserStatus.PENDING);

        adapter.onNewUserRegistered(new NewUserRegisteredEvent(user));

        verify(restClient.post()).uri(contains("/sendMessage"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void onNewUserRegistered_activeNonAdmin_sendsAutoApprovedInfoMessage() {
        // 승인 불필요 설정으로 즉시 ACTIVE 등록된 일반 사용자 — 관리자에게 정보성 알림
        User user = DomainFixtures.userWithStatus(UUID.randomUUID(), User.UserStatus.ACTIVE, User.UserRole.USER);
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);

        adapter.onNewUserRegistered(new NewUserRegisteredEvent(user));

        verify(restClient.post().uri(anyString())).body(bodyCaptor.capture());
        assertThat(((Map<String, String>) bodyCaptor.getValue()).get("text")).contains("자동 승인");
    }

    @Test
    void onNewUserRegistered_activeAdmin_skipsNotification() {
        // 관리자 seed 부트스트랩 — 알림 불필요
        User user = DomainFixtures.userWithStatus(UUID.randomUUID(), User.UserStatus.ACTIVE, User.UserRole.ADMIN);

        adapter.onNewUserRegistered(new NewUserRegisteredEvent(user));

        verifyNoInteractions(restClient);
    }

    // TradingReport 생성 헬퍼
    private TradingReport buildTestReport() {
        return new TradingReport(LocalDate.of(2024, 6, 15), Strategy.Type.INFINITE, Strategy.Ticker.SOXL, new BigDecimal("66.00"), new BigDecimal("35.00"));
    }
}
