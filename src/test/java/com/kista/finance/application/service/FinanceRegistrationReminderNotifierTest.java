package com.kista.finance.application.service;

import com.kista.finance.domain.model.AssetClass;
import com.kista.finance.domain.model.AssetSnapshot;
import com.kista.finance.domain.model.Market;
import com.kista.domain.model.user.NotificationType;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.model.user.UserSettings;
import com.kista.finance.application.port.output.AssetSnapshotPort;
import com.kista.finance.application.port.output.FinanceGroupPort;
import com.kista.finance.application.port.output.FinanceTransactionPort;
import com.kista.notify.application.port.output.UserNotificationPort;
import com.kista.application.port.output.UserPort;
import com.kista.application.port.output.UserSettingsPort;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinanceRegistrationReminderNotifierTest {

    @Test
    void 이번달_등록이_없는_유저에게만_알림을_보낸다() {
        UserPort userPort = mock(UserPort.class);
        FinanceGroupPort financeGroupPort = mock(FinanceGroupPort.class);
        UserSettingsPort userSettingsPort = mock(UserSettingsPort.class);
        UserNotificationPort notificationPort = mock(UserNotificationPort.class);
        AssetSnapshotPort assetSnapshotPort = mock(AssetSnapshotPort.class);
        FinanceTransactionPort financeTransactionPort = mock(FinanceTransactionPort.class);

        User userWithData = DomainFixtures.activeUser(UUID.randomUUID(), NotificationChannel.FCM);
        User userWithoutData = DomainFixtures.activeUser(UUID.randomUUID(), NotificationChannel.FCM);

        when(userPort.findAllByStatus(User.UserStatus.ACTIVE)).thenReturn(List.of(userWithData, userWithoutData));
        when(userSettingsPort.findOrDefaultByUserIds(any())).thenReturn(Map.of(
                userWithData.id(), UserSettings.defaultFor(userWithData.id()),
                userWithoutData.id(), UserSettings.defaultFor(userWithoutData.id())));
        when(financeGroupPort.findCurrentGroupId(any())).thenReturn(Optional.empty());
        AssetSnapshot existingSnapshot = new AssetSnapshot(UUID.randomUUID(), null, UUID.randomUUID(), null,
                userWithData.id(), java.time.LocalDate.of(2026, 8, 1), AssetClass.CASH, Market.DOMESTIC, null, null, 1000L, null);
        when(assetSnapshotPort.findMyScope(eq(userWithData.id()), any(), any(), any(), any()))
                .thenReturn(List.of(existingSnapshot));
        when(assetSnapshotPort.findMyScope(eq(userWithoutData.id()), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(financeTransactionPort.findMyScope(eq(userWithoutData.id()), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        var notifier = new FinanceRegistrationReminderNotifier(
                userPort, financeGroupPort, userSettingsPort, notificationPort,
                assetSnapshotPort, financeTransactionPort);

        notifier.notifyUsersWithoutThisMonthRegistration(YearMonth.of(2026, 8));

        verify(notificationPort, never()).notifyFinanceRegistrationReminder(eq(userWithData), any());
        verify(notificationPort, times(1)).notifyFinanceRegistrationReminder(eq(userWithoutData), eq("8월"));
    }

    @Test
    void 알림_비활성_유저에게는_보내지_않는다() {
        UserPort userPort = mock(UserPort.class);
        FinanceGroupPort financeGroupPort = mock(FinanceGroupPort.class);
        UserSettingsPort userSettingsPort = mock(UserSettingsPort.class);
        UserNotificationPort notificationPort = mock(UserNotificationPort.class);
        AssetSnapshotPort assetSnapshotPort = mock(AssetSnapshotPort.class);
        FinanceTransactionPort financeTransactionPort = mock(FinanceTransactionPort.class);

        User user = DomainFixtures.activeUser(UUID.randomUUID(), NotificationChannel.FCM);
        when(userPort.findAllByStatus(User.UserStatus.ACTIVE)).thenReturn(List.of(user));
        when(userSettingsPort.findOrDefaultByUserIds(any())).thenReturn(Map.of(
                user.id(), UserSettings.defaultFor(user.id()).withNotificationPrefs(Map.of(NotificationType.FINANCE_REMINDER, false))));

        var notifier = new FinanceRegistrationReminderNotifier(
                userPort, financeGroupPort, userSettingsPort, notificationPort,
                assetSnapshotPort, financeTransactionPort);

        notifier.notifyUsersWithoutThisMonthRegistration(YearMonth.of(2026, 8));

        verify(notificationPort, never()).notifyFinanceRegistrationReminder(any(), any());
    }
}
