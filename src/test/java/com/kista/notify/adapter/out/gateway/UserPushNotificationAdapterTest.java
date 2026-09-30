package com.kista.notify.adapter.out.gateway;

import com.kista.sharedkernel.NotificationChannel;
import com.kista.support.DomainFixtures;
import com.kista.user.application.port.output.UserPort;
import com.kista.user.domain.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserPushNotificationAdapterTest {

    @Mock UserPort userPort;
    @Mock FcmAdapter fcmAdapter;

    @Test
    void 사용자가_없으면_조용히_건너뛴다() {
        UUID userId = UUID.randomUUID();
        when(userPort.findById(userId)).thenReturn(Optional.empty());

        new UserPushNotificationAdapter(userPort, fcmAdapter).pushIfEnabled(userId, "제목", "본문");

        verifyNoInteractions(fcmAdapter);
    }

    @Test
    void FCM을_포함하지_않는_채널이면_보내지_않는다() {
        User user = DomainFixtures.activeUser(UUID.randomUUID(), NotificationChannel.TELEGRAM);
        when(userPort.findById(user.id())).thenReturn(Optional.of(user));

        new UserPushNotificationAdapter(userPort, fcmAdapter).pushIfEnabled(user.id(), "제목", "본문");

        verifyNoInteractions(fcmAdapter);
    }

    @Test
    void FCM을_포함하는_채널이면_푸시한다() {
        User user = DomainFixtures.activeUser(UUID.randomUUID(), NotificationChannel.ALL);
        when(userPort.findById(user.id())).thenReturn(Optional.of(user));

        new UserPushNotificationAdapter(userPort, fcmAdapter).pushIfEnabled(user.id(), "제목", "본문");

        verify(fcmAdapter).send(user.id(), "제목", "본문");
    }
}
