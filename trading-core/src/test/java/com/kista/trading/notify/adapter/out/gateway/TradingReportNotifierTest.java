package com.kista.trading.notify.adapter.out.gateway;

import com.kista.trading.application.port.output.TradingUserProfilePort;
import com.kista.trading.domain.model.TradingUserProfile;
import com.kista.trading.notify.application.port.output.TradingRealtimeNotificationPort;
import com.kista.trading.notify.application.port.output.TradingUserNotificationPort;
import com.kista.sharedkernel.TradingReportReadyEvent;
import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.TradeLegSummary;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.TradingReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import com.kista.sharedkernel.StrategyType;

@ExtendWith(MockitoExtension.class)
class TradingReportNotifierTest {

    @Mock TradingUserNotificationPort userNotificationPort;
    @Mock TradingRealtimeNotificationPort realtimeNotificationPort;
    @Mock TradingUserProfilePort userProfilePort;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final String ACCOUNT_NICKNAME = "테스트계좌";
    private static final TradingUserProfile PROFILE = new TradingUserProfile(USER_ID, Map.of(), true, "token", "chat");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 30);
    private static final TradingReport REPORT = new TradingReport(
            TODAY, StrategyType.INFINITE, StrategyTicker.SOXL, new BigDecimal("100.00"), new BigDecimal("50.00"));

    private static TradeLegSummary buyLeg() {
        return new TradeLegSummary(OrderDirection.BUY, StrategyTicker.SOXL, 3, new BigDecimal("20.00"), new BigDecimal("60.00"));
    }

    private static TradeLegSummary sellLeg() {
        return new TradeLegSummary(OrderDirection.SELL, StrategyTicker.SOXL, 2, new BigDecimal("21.00"), new BigDecimal("42.00"));
    }

    @BeforeEach
    void setUp() {
        lenient().when(userProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(PROFILE));
    }

    @Test
    void reportEnabled_true이면_리포트를_발송한다() {
        TradingReportNotifier notifier = new TradingReportNotifier(userNotificationPort, realtimeNotificationPort, userProfilePort);
        TradingReportReadyEvent event = new TradingReportReadyEvent(USER_ID, ACCOUNT_ID, ACCOUNT_NICKNAME, REPORT, List.of(), true);

        notifier.onTradingReportReady(event);

        verify(userNotificationPort).notifyTradingReport(PROFILE, ACCOUNT_NICKNAME, REPORT);
    }

    @Test
    void reportEnabled_false이면_리포트_발송을_생략한다() {
        TradingReportNotifier notifier = new TradingReportNotifier(userNotificationPort, realtimeNotificationPort, userProfilePort);
        TradingReportReadyEvent event = new TradingReportReadyEvent(USER_ID, ACCOUNT_ID, ACCOUNT_NICKNAME, REPORT, List.of(), false);

        notifier.onTradingReportReady(event);

        verify(userNotificationPort, never()).notifyTradingReport(any(), any(), any());
    }

    @Test
    void reportEnabled_false여도_SSE_알림은_항상_발송된다() {
        TradingReportNotifier notifier = new TradingReportNotifier(userNotificationPort, realtimeNotificationPort, userProfilePort);
        TradingReportReadyEvent event = new TradingReportReadyEvent(
                USER_ID, ACCOUNT_ID, ACCOUNT_NICKNAME, REPORT, List.of(buyLeg(), sellLeg()), false);

        notifier.onTradingReportReady(event);

        verify(userNotificationPort, never()).notifyTradingReport(any(), any(), any());
        verify(realtimeNotificationPort, times(2)).notifyTrade(eq(PROFILE.userId()), any());
    }

    @Test
    void BUY_체결_건별로_SSE_알림을_발송한다() {
        TradingReportNotifier notifier = new TradingReportNotifier(userNotificationPort, realtimeNotificationPort, userProfilePort);
        TradeLegSummary buy = buyLeg();
        TradingReportReadyEvent event = new TradingReportReadyEvent(USER_ID, ACCOUNT_ID, ACCOUNT_NICKNAME, REPORT, List.of(buy), true);

        notifier.onTradingReportReady(event);

        verify(realtimeNotificationPort, times(1)).notifyTrade(eq(PROFILE.userId()), any());
    }

    @Test
    void SELL_체결_건별로_SSE_알림을_발송한다() {
        TradingReportNotifier notifier = new TradingReportNotifier(userNotificationPort, realtimeNotificationPort, userProfilePort);
        TradeLegSummary sell = sellLeg();
        TradingReportReadyEvent event = new TradingReportReadyEvent(USER_ID, ACCOUNT_ID, ACCOUNT_NICKNAME, REPORT, List.of(sell), true);

        notifier.onTradingReportReady(event);

        verify(realtimeNotificationPort, times(1)).notifyTrade(eq(PROFILE.userId()), any());
    }

    @Test
    void BUY와_SELL이_섞이면_체결건수만큼_SSE_알림이_발송된다() {
        TradingReportNotifier notifier = new TradingReportNotifier(userNotificationPort, realtimeNotificationPort, userProfilePort);
        TradingReportReadyEvent event = new TradingReportReadyEvent(
                USER_ID, ACCOUNT_ID, ACCOUNT_NICKNAME, REPORT, List.of(buyLeg(), sellLeg()), true);

        notifier.onTradingReportReady(event);

        verify(realtimeNotificationPort, times(2)).notifyTrade(eq(PROFILE.userId()), any());
    }
}
