package com.kista.tradingcore.notify.adapter.out.gateway;

import com.kista.shared.kernel.Broker;
import com.kista.shared.platform.telegram.TelegramHttpClient;
import com.kista.tradingcore.account.application.event.ApiKeyExpiringEvent;
import com.kista.tradingcore.trading.domain.model.TradingUserProfile;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TradingUserNotificationAdapterWordingTest {

    TelegramHttpClient telegram = mock(TelegramHttpClient.class);
    TradingUserNotificationAdapter adapter = new TradingUserNotificationAdapter(telegram, mock(RedisPushNotificationPublisher.class));
    TradingUserProfile profile = new TradingUserProfile(UUID.randomUUID(), Map.of(), "tok", "chat");

    private ApiKeyExpiringEvent event(int daysLeft) {
        return new ApiKeyExpiringEvent(profile.userId(), UUID.randomUUID(), "모의", Broker.MOCK, LocalDate.of(2026, 10, 25), daysLeft);
    }

    @Test
    void todayWording() {
        adapter.notifyApiKeyExpiring(profile, event(0));
        verify(telegram).sendMessage("chat", "[모의] 모의계좌 API 키가 오늘 만료됩니다. 키를 새로 발급해 교체해 주세요.", "tok");
    }

    @Test
    void daysLeftWording() {
        adapter.notifyApiKeyExpiring(profile, event(7));
        verify(telegram).sendMessage("chat", "[모의] 모의계좌 API 키가 7일 후(2026-10-25) 만료됩니다.", "tok");
    }

    @Test
    void estimatedBrokerAppendsEditHint() {
        String hint = "실제 만료일과 다르면 계좌 설정에서 고칠 수 있습니다.";
        for (int days : new int[]{7, 0}) {
            TelegramHttpClient t = mock(TelegramHttpClient.class);
            new TradingUserNotificationAdapter(t, mock(RedisPushNotificationPublisher.class)).notifyApiKeyExpiring(profile,
                    new ApiKeyExpiringEvent(profile.userId(), UUID.randomUUID(), "토스", Broker.TOSS, LocalDate.of(2026, 10, 25), days));
            verify(t).sendMessage(org.mockito.ArgumentMatchers.eq("chat"),
                    org.mockito.ArgumentMatchers.endsWith(" " + hint), org.mockito.ArgumentMatchers.eq("tok"));
        }
    }

    @Test
    void sourceNoneHasNoEditHint() {
        adapter.notifyApiKeyExpiring(profile, event(7));
        verify(telegram, org.mockito.Mockito.never()).sendMessage(any(), org.mockito.ArgumentMatchers.contains("고칠 수"), any());
    }

}
