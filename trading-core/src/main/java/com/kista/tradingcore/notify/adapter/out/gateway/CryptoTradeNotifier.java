package com.kista.tradingcore.notify.adapter.out.gateway;

import com.kista.shared.contract.notify.TradeEventMessage;
import com.kista.shared.kernel.NotificationType;
import com.kista.shared.kernel.OrderDirection;
import com.kista.tradingcore.notify.application.port.output.TradingRealtimeNotificationPort;
import com.kista.tradingcore.notify.application.port.output.TradingUserNotificationPort;
import com.kista.tradingcore.trading.application.event.CryptoOrderFilledEvent;
import com.kista.tradingcore.trading.application.port.output.TradingUserProfilePort;
import com.kista.tradingcore.trading.domain.model.TradingUserProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// 코인 체결 알림 — Telegram/FCM은 TRADING_ALERT 설정을 따르고 SSE는 항상 발송(주식 리포트 알림과 동일 규칙)
@Component
@RequiredArgsConstructor
class CryptoTradeNotifier {

    private final TradingUserNotificationPort userNotificationPort;         // Telegram/FCM 발송
    private final TradingRealtimeNotificationPort realtimeNotificationPort; // SSE 실시간 알림
    private final TradingUserProfilePort userProfilePort;                   // ID → 프로필 재조회 (EPR 역직렬화 대응)

    @TransactionalEventListener(fallbackExecution = true)
    public void onCryptoOrderFilled(CryptoOrderFilledEvent e) {
        TradingUserProfile profile = TradingUserProfiles.requireProfile(userProfilePort, e.userId());
        if (profile.isNotificationEnabled(NotificationType.TRADING_ALERT)) {
            userNotificationPort.notifyCryptoTrade(profile, e);
        }
        String ticker = e.ticker().name();
        double price = e.avgPrice().doubleValue();
        double amount = e.amount().doubleValue();
        TradeEventMessage msg = e.direction() == OrderDirection.SELL
                ? TradeEventMessage.sell(ticker, e.filledQuantity(), price, amount, e.accountNickname(), e.accountId(), e.strategyId())
                : TradeEventMessage.buy(ticker, e.filledQuantity(), price, amount, e.accountNickname(), e.accountId(), e.strategyId());
        realtimeNotificationPort.notifyTrade(profile.userId(), msg);
    }
}
