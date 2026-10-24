package com.kista.tradingcore.notify.adapter.out.gateway;

import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.account.application.event.ApiKeyExpiringEvent;
import com.kista.tradingcore.notify.application.port.output.TradingUserNotificationPort;
import com.kista.tradingcore.trading.application.port.output.TradingUserProfilePort;
import com.kista.tradingcore.trading.domain.model.TradingUserProfile;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyExpiryNotifierTest {

    TradingUserNotificationPort notificationPort = mock(TradingUserNotificationPort.class);
    TradingUserProfilePort profilePort = mock(TradingUserProfilePort.class);
    ApiKeyExpiryNotifier notifier = new ApiKeyExpiryNotifier(notificationPort, profilePort);
    UUID userId = UUID.randomUUID();
    ApiKeyExpiringEvent event = new ApiKeyExpiringEvent(userId, UUID.randomUUID(), "업비트", Broker.UPBIT, LocalDate.of(2026, 10, 25), 7);

    @Test
    void loadsProfileAndNotifies() {
        TradingUserProfile profile = new TradingUserProfile(userId, Map.of(), "tok", "chat");
        when(profilePort.findByUserId(userId)).thenReturn(Optional.of(profile));

        notifier.onApiKeyExpiring(event);

        verify(notificationPort).notifyApiKeyExpiring(profile, event);
    }

    @Test
    void missingProfileThrowsSoEprRetries() {
        when(profilePort.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notifier.onApiKeyExpiring(event)).isInstanceOf(java.util.NoSuchElementException.class);
    }
}
