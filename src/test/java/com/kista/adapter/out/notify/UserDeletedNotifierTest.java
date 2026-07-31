package com.kista.adapter.out.notify;

import com.kista.application.event.UserDeletedEvent;
import com.kista.domain.port.out.NotifyPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserDeletedNotifierTest {

    @Mock NotifyPort notifyPort;

    @Test
    void onUserDeleted_notifiesAdminWithUserId() {
        UserDeletedNotifier notifier = new UserDeletedNotifier(notifyPort);
        UUID userId = UUID.randomUUID();
        UserDeletedEvent event = new UserDeletedEvent(userId);

        notifier.onUserDeleted(event);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(notifyPort).notifyInfo(captor.capture());
        assertThat(captor.getValue()).contains(userId.toString());
    }
}
