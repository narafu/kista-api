package com.kista.finance.application.service;

import com.kista.finance.application.event.FinanceRegistrationReminderDueEvent;
import com.kista.finance.application.port.output.AssetSnapshotPort;
import com.kista.finance.application.port.output.FinanceGroupPort;
import com.kista.finance.application.port.output.FinanceTransactionPort;
import com.kista.finance.domain.model.AssetClass;
import com.kista.finance.domain.model.AssetSnapshot;
import com.kista.finance.domain.model.Market;
import com.kista.sharedkernel.UserStatus;
import com.kista.user.application.port.output.UserPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinanceRegistrationReminderNotifierTest {

    @Test
    void 이번달_등록이_없는_유저에게만_알림을_요청한다() {
        UserPort userPort = mock(UserPort.class);
        FinanceGroupPort financeGroupPort = mock(FinanceGroupPort.class);
        AssetSnapshotPort assetSnapshotPort = mock(AssetSnapshotPort.class);
        FinanceTransactionPort financeTransactionPort = mock(FinanceTransactionPort.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

        UUID userWithData = UUID.randomUUID();
        UUID userWithoutData = UUID.randomUUID();

        when(userPort.findIdsByStatus(UserStatus.ACTIVE)).thenReturn(List.of(userWithData, userWithoutData));
        when(financeGroupPort.findCurrentGroupId(any())).thenReturn(Optional.empty());
        AssetSnapshot existingSnapshot = new AssetSnapshot(UUID.randomUUID(), null, UUID.randomUUID(), null,
                userWithData, java.time.LocalDate.of(2026, 8, 1), AssetClass.CASH, Market.DOMESTIC, null, null, 1000L, null);
        when(assetSnapshotPort.findMyScope(eq(userWithData), any(), any(), any(), any()))
                .thenReturn(List.of(existingSnapshot));
        when(assetSnapshotPort.findMyScope(eq(userWithoutData), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(financeTransactionPort.findMyScope(eq(userWithoutData), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        var notifier = new FinanceRegistrationReminderNotifier(
                userPort, financeGroupPort, assetSnapshotPort, financeTransactionPort, eventPublisher);

        notifier.notifyUsersWithoutThisMonthRegistration(YearMonth.of(2026, 8));

        ArgumentCaptor<FinanceRegistrationReminderDueEvent> captor = ArgumentCaptor.forClass(FinanceRegistrationReminderDueEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        FinanceRegistrationReminderDueEvent event = captor.getValue();
        assertThat(event.userId()).isEqualTo(userWithoutData);
        assertThat(event.title()).isEqualTo("가계부 등록을 아직 안 하셨어요");
        assertThat(event.body()).isEqualTo("📒 8월 가계부(자산·수입·소비·저축) 등록이 아직 없어요. 지금 등록해보세요.");
    }

    @Test
    void ACTIVE_유저가_없으면_이벤트를_발행하지_않는다() {
        UserPort userPort = mock(UserPort.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        when(userPort.findIdsByStatus(UserStatus.ACTIVE)).thenReturn(List.of());

        var notifier = new FinanceRegistrationReminderNotifier(
                userPort, mock(FinanceGroupPort.class), mock(AssetSnapshotPort.class),
                mock(FinanceTransactionPort.class), eventPublisher);

        notifier.notifyUsersWithoutThisMonthRegistration(YearMonth.of(2026, 8));

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }
}
