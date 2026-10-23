package com.kista.tradingcore.notify.adapter.out.gateway;

import com.kista.shared.contract.notify.TradeEventMessage;
import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.NotificationType;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.notify.application.port.output.TradingRealtimeNotificationPort;
import com.kista.tradingcore.notify.application.port.output.TradingUserNotificationPort;
import com.kista.tradingcore.trading.application.event.CryptoOrderFilledEvent;
import com.kista.tradingcore.trading.application.port.output.TradingUserProfilePort;
import com.kista.tradingcore.trading.domain.model.TradingUserProfile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CryptoTradeNotifierTest {

    TradingUserNotificationPort userPort = mock(TradingUserNotificationPort.class);
    TradingRealtimeNotificationPort realtime = mock(TradingRealtimeNotificationPort.class);
    TradingUserProfilePort profiles = mock(TradingUserProfilePort.class);
    CryptoTradeNotifier notifier = new CryptoTradeNotifier(userPort, realtime, profiles);
    UUID userId = UUID.randomUUID();
    CryptoOrderFilledEvent event = new CryptoOrderFilledEvent(userId, UUID.randomUUID(), UUID.randomUUID(), "업비트",
            StrategyTicker.BTC, OrderDirection.BUY, new BigDecimal("0.00012345"), new BigDecimal("150000000"), new BigDecimal("18518"), Currency.KRW);

    @Test
    void sendsTelegramAndSse() {
        TradingUserProfile p = new TradingUserProfile(userId, Map.of(), false, null, null);
        when(profiles.findByUserId(userId)).thenReturn(Optional.of(p));

        notifier.onCryptoOrderFilled(event);

        verify(userPort).notifyCryptoTrade(p, event);
        ArgumentCaptor<TradeEventMessage> msg = ArgumentCaptor.forClass(TradeEventMessage.class);
        verify(realtime).notifyTrade(org.mockito.ArgumentMatchers.eq(userId), msg.capture());
        assertThat(msg.getValue().quantity()).isEqualByComparingTo("0.00012345");
        assertThat(msg.getValue().kind()).isEqualTo(TradeEventMessage.Kind.BUY);
    }

    @Test
    void tradingAlertOffSkipsTelegramButKeepsSse() {
        TradingUserProfile p = new TradingUserProfile(userId, Map.of(NotificationType.TRADING_ALERT, false), false, null, null);
        when(profiles.findByUserId(userId)).thenReturn(Optional.of(p));

        notifier.onCryptoOrderFilled(event);

        verify(userPort, never()).notifyCryptoTrade(any(), any());
        verify(realtime).notifyTrade(org.mockito.ArgumentMatchers.eq(userId), any());
    }
}
