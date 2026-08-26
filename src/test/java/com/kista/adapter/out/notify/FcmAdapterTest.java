package com.kista.adapter.out.notify;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.MulticastMessage;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.port.out.FcmDeviceTokenPort;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FcmAdapterTest {

    @Mock FcmDeviceTokenPort fcmDeviceTokenPort;
    @Mock FirebaseMessaging firebaseMessaging;

    FcmAdapter adapter;

    static User user(UUID id) {
        return DomainFixtures.activeUser(id, NotificationChannel.FCM);
    }

    @BeforeEach
    void setUp() {
        adapter = new FcmAdapter(fcmDeviceTokenPort, Optional.of(firebaseMessaging));
    }

    @Test
    void send_noTokens_skips() {
        UUID userId = UUID.randomUUID();
        when(fcmDeviceTokenPort.findTokensByUserId(userId)).thenReturn(List.of());

        adapter.notifyApproved(user(userId));

        // 토큰 없으면 FirebaseMessaging 미호출
        verifyNoInteractions(firebaseMessaging);
    }

    @Test
    void send_firebaseEmpty_skips() {
        FcmAdapter noFirebaseAdapter = new FcmAdapter(fcmDeviceTokenPort, Optional.empty());
        UUID userId = UUID.randomUUID();

        noFirebaseAdapter.notifyApproved(user(userId));

        verifyNoInteractions(fcmDeviceTokenPort);
    }

    @Test
    void 가계부_미등록_알림을_전송한다() throws Exception {
        UUID userId = UUID.randomUUID();
        when(fcmDeviceTokenPort.findTokensByUserId(userId)).thenReturn(List.of("token-1"));
        BatchResponse batchResponse = mock(BatchResponse.class);
        when(batchResponse.getResponses()).thenReturn(List.of());
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(batchResponse);

        adapter.notifyFinanceRegistrationReminder(user(userId), "8월");

        verify(firebaseMessaging).sendEachForMulticast(any(MulticastMessage.class));
    }
}
