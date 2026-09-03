package com.kista.notify.adapter.out.gateway;

import com.kista.application.event.TradingReportReadyEvent;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.broker.Execution;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.strategy.TradingReport;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.RealtimeNotificationPort;
import com.kista.notify.domain.port.out.UserNotificationPort;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TradingReportNotifierTest {

    @Mock UserNotificationPort userNotificationPort;
    @Mock RealtimeNotificationPort realtimeNotificationPort;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final Account ACCOUNT = DomainFixtures.kisAccount(UUID.randomUUID(), USER_ID);
    private static final User USER = DomainFixtures.activeUserWithTelegram(USER_ID);
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 30);
    private static final TradingReport REPORT = new TradingReport(
            TODAY, Strategy.Type.INFINITE, Ticker.SOXL, new BigDecimal("100.00"), new BigDecimal("50.00"));

    private static Execution buyExecution() {
        return new Execution(TODAY, Ticker.SOXL, Order.OrderDirection.BUY,
                3, new BigDecimal("20.00"), new BigDecimal("60.00"), "E-BUY");
    }

    private static Execution sellExecution() {
        return new Execution(TODAY, Ticker.SOXL, Order.OrderDirection.SELL,
                2, new BigDecimal("21.00"), new BigDecimal("42.00"), "E-SELL");
    }

    @Test
    void reportEnabled_true이면_리포트를_발송한다() {
        TradingReportNotifier notifier = new TradingReportNotifier(userNotificationPort, realtimeNotificationPort);
        TradingReportReadyEvent event = new TradingReportReadyEvent(USER, ACCOUNT, REPORT, List.of(), true);

        notifier.onTradingReportReady(event);

        verify(userNotificationPort).notifyTradingReport(USER, ACCOUNT, REPORT);
    }

    @Test
    void reportEnabled_false이면_리포트_발송을_생략한다() {
        TradingReportNotifier notifier = new TradingReportNotifier(userNotificationPort, realtimeNotificationPort);
        TradingReportReadyEvent event = new TradingReportReadyEvent(USER, ACCOUNT, REPORT, List.of(), false);

        notifier.onTradingReportReady(event);

        verify(userNotificationPort, never()).notifyTradingReport(any(), any(), any());
    }

    @Test
    void reportEnabled_false여도_SSE_알림은_항상_발송된다() {
        TradingReportNotifier notifier = new TradingReportNotifier(userNotificationPort, realtimeNotificationPort);
        TradingReportReadyEvent event = new TradingReportReadyEvent(
                USER, ACCOUNT, REPORT, List.of(buyExecution(), sellExecution()), false);

        notifier.onTradingReportReady(event);

        verify(userNotificationPort, never()).notifyTradingReport(any(), any(), any());
        verify(realtimeNotificationPort, times(2)).notifyTrade(eq(USER.id()), any());
    }

    @Test
    void BUY_체결_건별로_SSE_알림을_발송한다() {
        TradingReportNotifier notifier = new TradingReportNotifier(userNotificationPort, realtimeNotificationPort);
        Execution buy = buyExecution();
        TradingReportReadyEvent event = new TradingReportReadyEvent(USER, ACCOUNT, REPORT, List.of(buy), true);

        notifier.onTradingReportReady(event);

        verify(realtimeNotificationPort, times(1)).notifyTrade(eq(USER.id()), any());
    }

    @Test
    void SELL_체결_건별로_SSE_알림을_발송한다() {
        TradingReportNotifier notifier = new TradingReportNotifier(userNotificationPort, realtimeNotificationPort);
        Execution sell = sellExecution();
        TradingReportReadyEvent event = new TradingReportReadyEvent(USER, ACCOUNT, REPORT, List.of(sell), true);

        notifier.onTradingReportReady(event);

        verify(realtimeNotificationPort, times(1)).notifyTrade(eq(USER.id()), any());
    }

    @Test
    void BUY와_SELL이_섞이면_체결건수만큼_SSE_알림이_발송된다() {
        TradingReportNotifier notifier = new TradingReportNotifier(userNotificationPort, realtimeNotificationPort);
        TradingReportReadyEvent event = new TradingReportReadyEvent(
                USER, ACCOUNT, REPORT, List.of(buyExecution(), sellExecution()), true);

        notifier.onTradingReportReady(event);

        verify(realtimeNotificationPort, times(2)).notifyTrade(eq(USER.id()), any());
    }
}
