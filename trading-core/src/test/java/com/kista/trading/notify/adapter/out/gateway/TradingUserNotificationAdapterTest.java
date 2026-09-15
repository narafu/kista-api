package com.kista.trading.notify.adapter.out.gateway;

import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.UserPushNotificationRequestedEvent;
import com.kista.trading.domain.model.TradingUserProfile;
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
            new TradingUserProfile(USER_ID, Map.of(), true, "bot-token", "chat-123");
    static final TradingUserProfile UNLINKED_PROFILE =
            new TradingUserProfile(USER_ID, Map.of(), true, null, null);

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
    void notifyMarketClose_unlinkedProfile_skipsTelegramButStillPublishesEvent() {
        adapter.notifyMarketClose(UNLINKED_PROFILE);

        verifyNoInteractions(restClient);
        verify(pushNotificationPublisher).publish(new UserPushNotificationRequestedEvent(USER_ID, "장 마감", "🔴 미국 장이 마감되었습니다."));
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyInsufficientBalance_bodyContainsAccountAndStrategyInfo() {
        adapter.notifyInsufficientBalance(LINKED_PROFILE, "내계좌", StrategyType.INFINITE, StrategyTicker.SOXL);

        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(restClient.post().uri(anyString())).body(captor.capture());
        String text = ((Map<String, String>) captor.getValue()).get("text");
        assertThat(text).contains("예수금 부족").contains("내계좌").contains("INFINITE").contains("SOXL");
    }
}
