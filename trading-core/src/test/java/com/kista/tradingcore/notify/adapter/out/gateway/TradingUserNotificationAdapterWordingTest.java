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
        return new ApiKeyExpiringEvent(profile.userId(), UUID.randomUUID(), "업비트", Broker.UPBIT, LocalDate.of(2026, 10, 25), daysLeft);
    }

    @Test
    void todayWording() {
        adapter.notifyApiKeyExpiring(profile, event(0));
        verify(telegram).sendMessage("chat", "[업비트] 업비트 API 키가 오늘 만료됩니다. 키를 새로 발급해 교체해 주세요.", "tok");
    }

    @Test
    void daysLeftWording() {
        adapter.notifyApiKeyExpiring(profile, event(7));
        verify(telegram).sendMessage("chat", "[업비트] 업비트 API 키가 7일 후(2026-10-25) 만료됩니다.", "tok");
    }

    @Test
    void estimatedBrokerAppendsEditHint() {
        String hint = "실제 만료일과 다르면 계좌 설정에서 고칠 수 있습니다.";
        for (int days : new int[]{7, 0}) {
            TelegramHttpClient t = mock(TelegramHttpClient.class);
            new TradingUserNotificationAdapter(t, mock(RedisPushNotificationPublisher.class)).notifyApiKeyExpiring(profile,
                    new ApiKeyExpiringEvent(profile.userId(), UUID.randomUUID(), "한투", Broker.KIS, LocalDate.of(2026, 10, 25), days));
            verify(t).sendMessage(org.mockito.ArgumentMatchers.eq("chat"),
                    org.mockito.ArgumentMatchers.endsWith(" " + hint), org.mockito.ArgumentMatchers.eq("tok"));
        }
    }

    @Test
    void brokerApiSourceHasNoEditHint() {
        adapter.notifyApiKeyExpiring(profile, event(7));
        verify(telegram, org.mockito.Mockito.never()).sendMessage(any(), org.mockito.ArgumentMatchers.contains("고칠 수"), any());
    }

    @Test
    void cryptoTradeWording() {
        adapter.notifyCryptoTrade(profile, new com.kista.tradingcore.trading.application.event.CryptoOrderFilledEvent(
                profile.userId(), UUID.randomUUID(), UUID.randomUUID(), "업비트", com.kista.shared.kernel.StrategyTicker.BTC,
                com.kista.shared.kernel.OrderDirection.BUY, new java.math.BigDecimal("0.00012345"),
                new java.math.BigDecimal("150000000"), new java.math.BigDecimal("15000"), com.kista.shared.kernel.Currency.KRW));
        verify(telegram).sendMessage("chat", "[업비트] BTC 매수 체결 — 수량 0.00012345, 평균가 150,000,000원, 금액 15,000원", "tok");
    }

    @Test
    void cryptoTradeWordingUsdt() {
        adapter.notifyCryptoTrade(profile, new com.kista.tradingcore.trading.application.event.CryptoOrderFilledEvent(
                profile.userId(), UUID.randomUUID(), UUID.randomUUID(), "바낸", com.kista.shared.kernel.StrategyTicker.BTC,
                com.kista.shared.kernel.OrderDirection.BUY, new java.math.BigDecimal("0.00016"),
                new java.math.BigDecimal("65000.123"), new java.math.BigDecimal("10.40"), com.kista.shared.kernel.Currency.USDT));
        verify(telegram).sendMessage("chat", "[바낸] BTC 매수 체결 — 수량 0.00016, 평균가 65,000.12 USDT, 금액 10.40 USDT", "tok");
    }
}
