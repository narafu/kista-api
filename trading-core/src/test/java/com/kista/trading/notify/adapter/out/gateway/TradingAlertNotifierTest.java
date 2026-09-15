package com.kista.trading.notify.adapter.out.gateway;

import com.kista.trading.application.port.output.TradingUserProfilePort;
import com.kista.trading.domain.model.TradingUserProfile;
import com.kista.trading.notify.application.port.output.TradingNotifyPort;
import com.kista.trading.notify.application.port.output.TradingUserNotificationPort;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.BatchInterruptedEvent;
import com.kista.sharedkernel.InsufficientBalanceEvent;
import com.kista.sharedkernel.MarketClosedEvent;
import com.kista.sharedkernel.MarketCloseEvent;
import com.kista.sharedkernel.MarketOpenEvent;
import com.kista.sharedkernel.TradingErrorEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.kista.sharedkernel.StrategyType;

// trading이 발행하는 관리자/사용자 알림 이벤트 6종이 TradingNotifyPort/TradingUserNotificationPort로 정확히 라우팅되는지 검증
@ExtendWith(MockitoExtension.class)
class TradingAlertNotifierTest {

    @Mock TradingNotifyPort notifyPort;
    @Mock TradingUserNotificationPort userNotificationPort;
    @Mock TradingUserProfilePort userProfilePort;

    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final String accountNickname = "테스트계좌";
    private final TradingUserProfile profile = new TradingUserProfile(userId, Map.of(), true, "token", "chat");

    private TradingAlertNotifier notifier() {
        return new TradingAlertNotifier(notifyPort, userNotificationPort, userProfilePort);
    }

    @Test
    void onTradingError_adminPath_callsNotifyPortWhenUserIsNull() {
        notifier().onTradingError(new TradingErrorEvent(null, "배치 오류"));

        verify(notifyPort).notifyError(argThat(e -> "배치 오류".equals(e.getMessage())));
        verify(userNotificationPort, never()).notifyError(any(), any());
    }

    @Test
    void onTradingError_userPath_callsUserNotificationPortWhenUserPresent() {
        when(userProfilePort.findByUserId(userId)).thenReturn(Optional.of(profile));

        notifier().onTradingError(new TradingErrorEvent(userId, "사용자 매매 오류"));

        verify(userNotificationPort).notifyError(eq(profile), argThat(e -> "사용자 매매 오류".equals(e.getMessage())));
        verify(notifyPort, never()).notifyError(any());
    }

    @Test
    void onInsufficientBalance_adminPath_callsNotifyPortWithScalars() {
        BigDecimal usdDeposit = new BigDecimal("100.00");

        notifier().onInsufficientBalance(new InsufficientBalanceEvent(null, accountId, accountNickname,
                0, usdDeposit, StrategyTicker.SOXL, null));

        verify(notifyPort).notifyInsufficientBalance(0, usdDeposit, StrategyTicker.SOXL);
        verify(userNotificationPort, never()).notifyInsufficientBalance(any(), any(), any(), any());
    }

    @Test
    void onInsufficientBalance_userPath_callsUserNotificationPortWithStrategyType() {
        when(userProfilePort.findByUserId(userId)).thenReturn(Optional.of(profile));

        notifier().onInsufficientBalance(
                new InsufficientBalanceEvent(userId, accountId, accountNickname, 0, null, StrategyTicker.SOXL, StrategyType.INFINITE));

        verify(userNotificationPort).notifyInsufficientBalance(profile, accountNickname, StrategyType.INFINITE, StrategyTicker.SOXL);
        verify(notifyPort, never()).notifyInsufficientBalance(anyInt(), any(), any());
    }

    @Test
    void onMarketClosed_callsNotifyPort() {
        notifier().onMarketClosed(new MarketClosedEvent());

        verify(notifyPort).notifyMarketClosed();
    }

    @Test
    void onMarketOpen_callsUserNotificationPort() {
        when(userProfilePort.findByUserId(userId)).thenReturn(Optional.of(profile));

        notifier().onMarketOpen(new MarketOpenEvent(userId));

        verify(userNotificationPort).notifyMarketOpen(profile);
    }

    @Test
    void onMarketClose_callsUserNotificationPort() {
        when(userProfilePort.findByUserId(userId)).thenReturn(Optional.of(profile));

        notifier().onMarketClose(new MarketCloseEvent(userId));

        verify(userNotificationPort).notifyMarketClose(profile);
    }

    @Test
    void onBatchInterrupted_callsUserNotificationPort() {
        when(userProfilePort.findByUserId(userId)).thenReturn(Optional.of(profile));

        notifier().onBatchInterrupted(new BatchInterruptedEvent(userId, accountId, accountNickname));

        verify(userNotificationPort).notifyBatchInterrupted(profile, accountNickname);
    }
}
