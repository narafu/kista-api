package com.kista.notify.adapter.in.redis;

import com.kista.notify.application.port.output.PushNotificationPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class LegacyPushNotificationRelayListenerTest {

    @Mock RedisMessageListenerContainer listenerContainer;
    @Mock PushNotificationPort pushNotificationPort;

    private LegacyPushNotificationRelayListener listener() {
        return new LegacyPushNotificationRelayListener(listenerContainer, pushNotificationPort, new ObjectMapper());
    }

    private static DefaultMessage message(String body) {
        return new DefaultMessage("user.push-notification.requested".getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void 옛_채널_메시지를_포트에_위임한다() {
        UUID userId = UUID.randomUUID();

        listener().onMessage(message("{\"userId\":\"" + userId + "\",\"title\":\"체결\",\"body\":\"SOXL 매수 체결\"}"), null);

        verify(pushNotificationPort).pushIfEnabled(userId, "체결", "SOXL 매수 체결");
    }

    @Test
    void 역직렬화_불가_메시지는_예외없이_무시한다() {
        assertThatCode(() -> listener().onMessage(message("not-json"), null)).doesNotThrowAnyException();

        verifyNoInteractions(pushNotificationPort);
    }
}
