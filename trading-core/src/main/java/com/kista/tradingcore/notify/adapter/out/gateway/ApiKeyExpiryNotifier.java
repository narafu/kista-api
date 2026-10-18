package com.kista.tradingcore.notify.adapter.out.gateway;

import com.kista.tradingcore.account.application.event.ApiKeyExpiringEvent;
import com.kista.tradingcore.notify.application.port.output.TradingUserNotificationPort;
import com.kista.tradingcore.trading.application.port.output.TradingUserProfilePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// API 키 만료 알림 — 스케쥴러가 트랜잭션 밖에서 발행하므로 fallbackExecution 필수. 키가 만료되면 매매가 멈추므로 TRADING_ALERT 설정과 무관하게 발송
@Component
@RequiredArgsConstructor
class ApiKeyExpiryNotifier {

    private final TradingUserNotificationPort userNotificationPort; // Telegram/FCM 발송
    private final TradingUserProfilePort userProfilePort;           // ID → 프로필 재조회

    @TransactionalEventListener(fallbackExecution = true)
    public void onApiKeyExpiring(ApiKeyExpiringEvent e) {
        userNotificationPort.notifyApiKeyExpiring(TradingUserProfiles.requireProfile(userProfilePort, e.userId()), e);
    }
}
