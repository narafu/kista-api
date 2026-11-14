package com.kista.api.notify.adapter.out.gateway;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.kista.api.notify.application.port.output.FcmDeviceTokenPort;
import com.kista.api.notify.application.port.output.PushNotificationPort;
import com.kista.api.notify.domain.model.NotificationRecipient;
import com.kista.api.support.DomainFixtures;
import com.kista.api.user.application.port.output.UserPort;
import com.kista.api.user.application.port.output.UserSettingsPort;
import com.kista.api.user.domain.model.User;
import com.kista.api.user.domain.model.UserSettings;
import com.kista.shared.kernel.NotificationChannel;
import com.kista.shared.platform.telegram.TelegramHttpClient;
import com.kista.shared.platform.telegram.TelegramProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 특성화(characterization) 테스트 — 문구 조립기 리팩토링 전 "지금 코드가 내는 최종 문자열"을 고정한다.
// 문구가 어색해 보여도 고치지 않는다. 관리자 전용 알림(신규 가입 신청·자동 승인·스케쥴러·오류)은 대상이 아니다.
// 가계부 리마인더의 제목·본문 조립은 finance FinanceRegistrationReminderNotifierTest가 전체 일치로 고정하고, 여기서는 같은 문자열을 채널 라우팅에 통과시킨다.
class NotificationMessageSnapshotTest {

    static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final NotificationRecipient APPROVED_USER =
            new NotificationRecipient(USER_ID, "닉네임", NotificationChannel.ALL, "user-tok", "user-chat", null);

    private static NotificationRecipient rejected(String reason) {
        return new NotificationRecipient(USER_ID, "닉네임", NotificationChannel.ALL, "user-tok", "user-chat", reason);
    }

    // ── 텔레그램 사용자 봇 (TelegramUserNotificationAdapter) ──

    private String telegramText(java.util.function.Consumer<TelegramUserNotificationAdapter> call) {
        TelegramHttpClient http = mock(TelegramHttpClient.class);
        call.accept(new TelegramUserNotificationAdapter(http, new TelegramProperties("admin-token", "admin-chat")));
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(http).sendMessage(eq("user-chat"), text.capture(), eq("user-tok"));
        return text.getValue();
    }

    @Test
    @DisplayName("UserApprovedEvent → notifyApproved — 텔레그램 가입 승인")
    void telegramApproved() {
        assertThat(telegramText(a -> a.notifyApproved(APPROVED_USER))).isEqualTo("✅ 가입이 승인되었습니다.");
    }

    @Test
    @DisplayName("UserRejectedEvent → notifyRejected — 텔레그램 가입 거절, 사유 없음")
    void telegramRejectedNoReason() {
        assertThat(telegramText(a -> a.notifyRejected(rejected(null)))).isEqualTo("❌ 가입 신청이 거절되었습니다.");
    }

    @Test
    @DisplayName("UserRejectedEvent → notifyRejected — 텔레그램 가입 거절, 사유 있음(HTML 이스케이프)")
    void telegramRejectedWithReason() {
        assertThat(telegramText(a -> a.notifyRejected(rejected("<b>서류</b> & 미비")))).isEqualTo("""
                ❌ 가입 신청이 거절되었습니다.
                사유: &lt;b&gt;서류&lt;/b&gt; &amp; 미비""");
    }

    // ── FCM (FcmAdapter) — 제목·본문은 Notification 내부 필드라 리플렉션으로 읽는다 ──

    private String[] fcmTitleAndBody(java.util.function.Consumer<FcmAdapter> call) throws Exception {
        FcmDeviceTokenPort tokens = mock(FcmDeviceTokenPort.class);
        when(tokens.findTokensByUserId(USER_ID)).thenReturn(List.of("t1"));
        FirebaseMessaging firebase = mock(FirebaseMessaging.class);
        BatchResponse response = mock(BatchResponse.class);
        when(response.getResponses()).thenReturn(List.of());
        when(firebase.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(response);
        call.accept(new FcmAdapter(tokens, Optional.of(firebase)));
        ArgumentCaptor<MulticastMessage> message = ArgumentCaptor.forClass(MulticastMessage.class);
        verify(firebase).sendEachForMulticast(message.capture());
        Notification notification = (Notification) field(message.getValue(), "notification");
        return new String[]{(String) field(notification, "title"), (String) field(notification, "body")};
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    @Test
    @DisplayName("UserApprovedEvent → notifyApproved — FCM 가입 승인")
    void fcmApproved() throws Exception {
        assertThat(fcmTitleAndBody(a -> a.notifyApproved(APPROVED_USER)))
                .containsExactly("KISTA 알림", "✅ 가입이 승인되었습니다.");
    }

    @Test
    @DisplayName("UserRejectedEvent → notifyRejected — FCM 가입 거절(사유가 있어도 문구에 넣지 않음)")
    void fcmRejected() throws Exception {
        assertThat(fcmTitleAndBody(a -> a.notifyRejected(rejected("서류 미비"))))
                .containsExactly("KISTA 알림", "❌ 가입이 거절되었습니다.");
    }

    // ── 가계부 리마인더 (FinanceRegistrationReminderDueEvent → FinanceReminderChannelNotifier → UserChannelNotifier) ──

    @Test
    @DisplayName("FinanceRegistrationReminderDueEvent — 가계부 등록 리마인더, 텔레그램 본문과 FCM 제목·본문")
    void financeReminder() {
        UserPort userPort = mock(UserPort.class);
        UserSettingsPort settingsPort = mock(UserSettingsPort.class);
        TelegramHttpClient http = mock(TelegramHttpClient.class);
        PushNotificationPort push = mock(PushNotificationPort.class);
        User user = DomainFixtures.activeUser(USER_ID, NotificationChannel.ALL).withTelegram("user-tok", "user-chat", null);
        when(userPort.findById(USER_ID)).thenReturn(Optional.of(user));
        when(settingsPort.findOrDefault(USER_ID)).thenReturn(UserSettings.defaultFor(USER_ID));

        new FinanceReminderChannelNotifier(new UserChannelNotifier(userPort, settingsPort, http, push))
                .onReminderDue(new com.kista.api.finance.application.event.FinanceRegistrationReminderDueEvent(USER_ID,
                        "가계부 등록을 아직 안 하셨어요", "📒 8월 가계부(자산·수입·소비·저축) 등록이 아직 없어요. 지금 등록해보세요."));

        verify(http).sendMessage("user-chat", "📒 8월 가계부(자산·수입·소비·저축) 등록이 아직 없어요. 지금 등록해보세요.", "user-tok");
        verify(push).pushIfEnabled(USER_ID, "가계부 등록을 아직 안 하셨어요",
                "📒 8월 가계부(자산·수입·소비·저축) 등록이 아직 없어요. 지금 등록해보세요.");
    }
}
