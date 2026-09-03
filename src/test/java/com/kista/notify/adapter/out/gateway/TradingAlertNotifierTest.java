package com.kista.notify.adapter.out.gateway;

import com.kista.account.application.port.output.AccountPort;
import com.kista.user.application.port.output.UserPort;
import com.kista.account.domain.model.Account;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.user.domain.model.User;
import com.kista.notify.application.port.output.NotifyPort;
import com.kista.notify.application.port.output.UserNotificationPort;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.kista.sharedkernel.StrategyType;

// trading이 발행하는 관리자/사용자 알림 이벤트 6종이 기존 NotifyPort/UserNotificationPort 메서드로 정확히 라우팅되는지 검증
@ExtendWith(MockitoExtension.class)
class TradingAlertNotifierTest {

    @Mock NotifyPort notifyPort;
    @Mock UserNotificationPort userNotificationPort;
    @Mock UserPort userPort;
    @Mock AccountPort accountPort;

    private final UUID userId = UUID.randomUUID();
    private final Account account = DomainFixtures.kisAccount(UUID.randomUUID(), userId);
    private final User user = DomainFixtures.activeUserWithTelegram(userId);

    private TradingAlertNotifier notifier() {
        return new TradingAlertNotifier(notifyPort, userNotificationPort, userPort, accountPort);
    }

    @Test
    void onTradingError_adminPath_callsNotifyPortWhenUserIsNull() {
        notifier().onTradingError(new TradingErrorEvent(null, "배치 오류"));

        verify(notifyPort).notifyError(argThat(e -> "배치 오류".equals(e.getMessage())));
        verify(userNotificationPort, never()).notifyError(any(), any());
    }

    @Test
    void onTradingError_userPath_callsUserNotificationPortWhenUserPresent() {
        when(userPort.findByIdOrThrow(userId)).thenReturn(user);

        notifier().onTradingError(new TradingErrorEvent(userId, "사용자 매매 오류"));

        verify(userNotificationPort).notifyError(eq(user), argThat(e -> "사용자 매매 오류".equals(e.getMessage())));
        verify(notifyPort, never()).notifyError(any());
    }

    @Test
    void onInsufficientBalance_adminPath_callsNotifyPortWithAccountBalance() {
        when(accountPort.findByIdOrThrow(account.id())).thenReturn(account);
        AccountBalance balance = new AccountBalance(0, null, new BigDecimal("100.00"));

        notifier().onInsufficientBalance(new InsufficientBalanceEvent(null, account.id(), balance, StrategyTicker.SOXL, null));

        verify(notifyPort).notifyInsufficientBalance(account, balance, StrategyTicker.SOXL);
        verify(userNotificationPort, never()).notifyInsufficientBalance(any(), any(), any(), any());
    }

    @Test
    void onInsufficientBalance_userPath_callsUserNotificationPortWithStrategyType() {
        when(accountPort.findByIdOrThrow(account.id())).thenReturn(account);
        when(userPort.findByIdOrThrow(userId)).thenReturn(user);

        notifier().onInsufficientBalance(
                new InsufficientBalanceEvent(userId, account.id(), null, StrategyTicker.SOXL, StrategyType.INFINITE));

        verify(userNotificationPort).notifyInsufficientBalance(user, account, StrategyType.INFINITE, StrategyTicker.SOXL);
        verify(notifyPort, never()).notifyInsufficientBalance(any(), any(), any());
    }

    @Test
    void onMarketClosed_callsNotifyPort() {
        notifier().onMarketClosed(new MarketClosedEvent());

        verify(notifyPort).notifyMarketClosed();
    }

    @Test
    void onMarketOpen_callsUserNotificationPort() {
        when(userPort.findByIdOrThrow(userId)).thenReturn(user);

        notifier().onMarketOpen(new MarketOpenEvent(userId));

        verify(userNotificationPort).notifyMarketOpen(user);
    }

    @Test
    void onMarketClose_callsUserNotificationPort() {
        when(userPort.findByIdOrThrow(userId)).thenReturn(user);

        notifier().onMarketClose(new MarketCloseEvent(userId));

        verify(userNotificationPort).notifyMarketClose(user);
    }

    @Test
    void onBatchInterrupted_callsUserNotificationPort() {
        when(userPort.findByIdOrThrow(userId)).thenReturn(user);
        when(accountPort.findByIdOrThrow(account.id())).thenReturn(account);

        notifier().onBatchInterrupted(new BatchInterruptedEvent(userId, account.id()));

        verify(userNotificationPort).notifyBatchInterrupted(user, account);
    }
}
