package com.kista.adapter.out.notify;

import com.google.firebase.messaging.FirebaseMessaging;
import com.kista.domain.model.user.NotificationChannel;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.FcmDeviceTokenPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FcmAdapterTest {

    @Mock FcmDeviceTokenPort fcmDeviceTokenPort;
    @Mock FirebaseMessaging firebaseMessaging;

    FcmAdapter adapter;

    static User user(UUID id) {
        return new User(id, "kakao-1", "홍길동", User.UserStatus.ACTIVE, User.UserRole.USER,
                null, null, null, null, NotificationChannel.FCM);
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
}
