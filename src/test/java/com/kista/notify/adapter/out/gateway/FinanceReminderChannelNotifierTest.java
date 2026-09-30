package com.kista.notify.adapter.out.gateway;

import com.kista.finance.application.event.FinanceRegistrationReminderDueEvent;
import com.kista.sharedkernel.NotificationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FinanceReminderChannelNotifierTest {

    @Mock UserChannelNotifier userChannelNotifier;

    @Test
    void 리마인더_이벤트를_FINANCE_REMINDER_유형으로_공용_라우터에_위임한다() {
        UUID userId = UUID.randomUUID();

        new FinanceReminderChannelNotifier(userChannelNotifier)
                .onReminderDue(new FinanceRegistrationReminderDueEvent(userId, "제목", "📒 본문"));

        verify(userChannelNotifier).notify(userId, NotificationType.FINANCE_REMINDER, "제목", "📒 본문");
    }
}
