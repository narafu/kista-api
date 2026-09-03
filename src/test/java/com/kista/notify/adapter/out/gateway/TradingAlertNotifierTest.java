package com.kista.notify.adapter.out.gateway;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.user.User;
import com.kista.notify.domain.port.out.NotifyPort;
import com.kista.notify.domain.port.out.UserNotificationPort;
import com.kista.support.DomainFixtures;
import com.kista.trading.application.event.BatchInterruptedEvent;
import com.kista.trading.application.event.InsufficientBalanceEvent;
import com.kista.trading.application.event.MarketClosedEvent;
import com.kista.trading.application.event.MarketCloseEvent;
import com.kista.trading.application.event.MarketOpenEvent;
import com.kista.trading.application.event.TradingErrorEvent;
import com.kista.trading.domain.model.AccountBalance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// trading이 발행하는 관리자/사용자 알림 이벤트 6종이 기존 NotifyPort/UserNotificationPort 메서드로 정확히 라우팅되는지 검증
@ExtendWith(MockitoExtension.class)
class TradingAlertNotifierTest {

    @Mock NotifyPort notifyPort;
    @Mock UserNotificationPort userNotificationPort;

    private final UUID userId = UUID.randomUUID();
    private final Account account = DomainFixtures.kisAccount(UUID.randomUUID(), userId);
    private final User user = DomainFixtures.activeUserWithTelegram(userId);

    private TradingAlertNotifier notifier() {
        return new TradingAlertNotifier(notifyPort, userNotificationPort);
    }

    @Test
    void onTradingError_adminPath_callsNotifyPortWhenUserIsNull() {
        Exception e = new IllegalStateException("배치 오류");

        notifier().onTradingError(new TradingErrorEvent(null, e));

        verify(notifyPort).notifyError(e);
        verify(userNotificationPort, never()).notifyError(any(), any());
    }

    @Test
    void onTradingError_userPath_callsUserNotificationPortWhenUserPresent() {
        Exception e = new IllegalStateException("사용자 매매 오류");

        notifier().onTradingError(new TradingErrorEvent(user, e));

        verify(userNotificationPort).notifyError(user, e);
        verify(notifyPort, never()).notifyError(any());
    }

    @Test
    void onInsufficientBalance_adminPath_callsNotifyPortWithAccountBalance() {
        AccountBalance balance = new AccountBalance(0, null, new BigDecimal("100.00"));

        notifier().onInsufficientBalance(new InsufficientBalanceEvent(null, account, balance, Ticker.SOXL, null));

        verify(notifyPort).notifyInsufficientBalance(account, balance, Ticker.SOXL);
        verify(userNotificationPort, never()).notifyInsufficientBalance(any(), any(), any(), any());
    }

    @Test
    void onInsufficientBalance_userPath_callsUserNotificationPortWithStrategyType() {
        notifier().onInsufficientBalance(
                new InsufficientBalanceEvent(user, account, null, Ticker.SOXL, Strategy.Type.INFINITE));

        verify(userNotificationPort).notifyInsufficientBalance(user, account, Strategy.Type.INFINITE, Ticker.SOXL);
        verify(notifyPort, never()).notifyInsufficientBalance(any(), any(), any());
    }

    @Test
    void onMarketClosed_callsNotifyPort() {
        notifier().onMarketClosed(new MarketClosedEvent());

        verify(notifyPort).notifyMarketClosed();
    }

    @Test
    void onMarketOpen_callsUserNotificationPort() {
        notifier().onMarketOpen(new MarketOpenEvent(user));

        verify(userNotificationPort).notifyMarketOpen(user);
    }

    @Test
    void onMarketClose_callsUserNotificationPort() {
        notifier().onMarketClose(new MarketCloseEvent(user));

        verify(userNotificationPort).notifyMarketClose(user);
    }

    @Test
    void onBatchInterrupted_callsUserNotificationPort() {
        notifier().onBatchInterrupted(new BatchInterruptedEvent(user, account));

        verify(userNotificationPort).notifyBatchInterrupted(user, account);
    }
}
