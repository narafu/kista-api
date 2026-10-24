package com.kista.tradingcore.notify.adapter.out.gateway;

import com.kista.shared.kernel.NotificationChannel;
import com.kista.shared.platform.telegram.TelegramHttpClient;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.UserPushNotificationRequestedEvent;
import com.kista.tradingcore.trading.domain.model.TradingUserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class TradingUserNotificationAdapterTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    RestClient restClient;
    @Mock
    RedisPushNotificationPublisher pushNotificationPublisher;

    TradingUserNotificationAdapter adapter;

    static final UUID USER_ID = UUID.randomUUID();
    static final TradingUserProfile LINKED_PROFILE =
            new TradingUserProfile(USER_ID, Map.of(), "bot-token", "chat-123");
    static final TradingUserProfile UNLINKED_PROFILE =
            new TradingUserProfile(USER_ID, Map.of(), null, null);

    @BeforeEach
    void setUp() {
        TelegramHttpClient httpClient = new TelegramHttpClient(restClient);
        adapter = new TradingUserNotificationAdapter(httpClient, pushNotificationPublisher);
    }

    @Test
    void notifyMarketOpen_linkedProfile_sendsTelegramAndPublishesEvent() {
        adapter.notifyMarketOpen(LINKED_PROFILE);

        verify(restClient.post()).uri(contains("/bot" + "bot-token" + "/sendMessage"));
        verify(pushNotificationPublisher).publish(new UserPushNotificationRequestedEvent(USER_ID, "장 개시", "🟢 미국 장이 열렸습니다."));
    }

    @Test
    void notifyMarketOpen_channelWithoutTelegram_skipsTelegramButStillPublishesEvent() {
        // 봇이 연결돼 있어도 알림 채널이 FCM이면 텔레그램은 보내지 않는다
        adapter.notifyMarketOpen(new TradingUserProfile(USER_ID, Map.of(), "bot-token", "chat-123", NotificationChannel.FCM));

        verifyNoInteractions(restClient);
        verify(pushNotificationPublisher).publish(new UserPushNotificationRequestedEvent(USER_ID, "장 개시", "🟢 미국 장이 열렸습니다."));
    }

    @Test
    void notifyMarketClose_unlinkedProfile_skipsTelegramButStillPublishesEvent() {
        adapter.notifyMarketClose(UNLINKED_PROFILE);

        verifyNoInteractions(restClient);
        verify(pushNotificationPublisher).publish(new UserPushNotificationRequestedEvent(USER_ID, "장 마감", "🔴 미국 장이 마감되었습니다."));
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyInsufficientBalance_brokerRejection_bodyContainsFailedCountAndPartialNote() {
        adapter.notifyInsufficientBalance(LINKED_PROFILE, "내계좌", StrategyType.INFINITE, StrategyTicker.SOXL, 2, true);

        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(restClient.post().uri(anyString())).body(captor.capture());
        String text = ((Map<String, String>) captor.getValue()).get("text");
        assertThat(text).contains("증권사 주문 가능 금액 부족").contains("내계좌").contains("INFINITE").contains("SOXL")
                .contains("매수 2건 실패 (일부 접수)").contains("예수금 확인 바랍니다.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyInsufficientBalance_ledgerCap_bodyExplainsNoOrderToday() {
        adapter.notifyInsufficientBalance(LINKED_PROFILE, "내계좌", StrategyType.INFINITE, StrategyTicker.SOXL, 0, false);

        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(restClient.post().uri(anyString())).body(captor.capture());
        String text = ((Map<String, String>) captor.getValue()).get("text");
        assertThat(text).contains("전략 원장 예수금 부족").contains("내계좌").contains("오늘 매수 주문을 내지 않았습니다.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyDcaRoundSkipped_bodyContainsReasonDirectionAndDetail() {
        var event = new com.kista.tradingcore.trading.application.event.DcaRoundSkippedEvent(
                UUID.randomUUID(), UUID.randomUUID(), "내계좌", StrategyTicker.UPRO,
                "rule-key", 2, com.kista.shared.kernel.OrderDirection.SELL, java.time.LocalDate.of(2026, 10, 14),
                com.kista.shared.kernel.DcaSkipReason.NO_HOLDINGS, "판매가능수량 0주");

        adapter.notifyDcaRoundSkipped(LINKED_PROFILE, event);

        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(restClient.post().uri(anyString())).body(captor.capture());
        String text = ((Map<String, String>) captor.getValue()).get("text");
        assertThat(text).contains("DCA 미주문").contains("내계좌").contains("UPRO")
                .contains("2026-10-14").contains("보유 수량 없음").contains("판매가능수량 0주")
                .contains("매도 DCA 규칙 2"); // 방향·규칙 순번
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyDcaRoundSkipped_nicknameAndDetailWithHtmlChars_areEscaped() {
        // parse_mode=HTML — 미이스케이프 '<'·'&'는 텔레그램 400으로 알림 유실, 닉네임은 마크업 주입 경로
        var event = new com.kista.tradingcore.trading.application.event.DcaRoundSkippedEvent(
                UUID.randomUUID(), UUID.randomUUID(), "<b>A&B</b>", StrategyTicker.UPRO,
                "rule-key", 2, com.kista.shared.kernel.OrderDirection.BUY, java.time.LocalDate.of(2026, 10, 14),
                com.kista.shared.kernel.DcaSkipReason.INSUFFICIENT_BALANCE, "예수금 부족(주문가능금액 < 1주 캡가)");

        adapter.notifyDcaRoundSkipped(LINKED_PROFILE, event);

        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(restClient.post().uri(anyString())).body(captor.capture());
        String text = ((Map<String, String>) captor.getValue()).get("text");
        assertThat(text).contains("&lt;b&gt;A&amp;B&lt;/b&gt;").contains("주문가능금액 &lt; 1주 캡가")
                .doesNotContain("<b>A&B");
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyTradingReport_nicknameWithHtmlChars_isEscaped() {
        var report = new com.kista.tradingcore.trading.domain.model.TradingReport(java.time.LocalDate.of(2026, 10, 14),
                StrategyType.INFINITE, StrategyTicker.SOXL, java.math.BigDecimal.ONE, java.math.BigDecimal.ZERO);

        adapter.notifyTradingReport(LINKED_PROFILE, "<i>내계좌</i>", report);

        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(restClient.post().uri(anyString())).body(captor.capture());
        String text = ((Map<String, String>) captor.getValue()).get("text");
        assertThat(text).contains("&lt;i&gt;내계좌&lt;/i&gt;").doesNotContain("<i>");
        // FCM은 HTML을 렌더링하지 않아 태그를 지우고 엔티티를 되돌린 평문으로 위임한다
        verify(pushNotificationPublisher).publish(new UserPushNotificationRequestedEvent(USER_ID, "매매 결산",
                "매매 결산[2026-10-14] — <i>내계좌</i>%n[INFINITE] SOXL 매수: $1.00 | 매도: $0.00".formatted()));
    }
}
