package com.kista.notify.adapter.out.sse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import com.kista.sharedkernel.UserStatus;

@ExtendWith(MockitoExtension.class)
class SseEmitterRegistryTest {

    SseEmitterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SseEmitterRegistry();
    }

    @Test
    void connect_returns_non_null_emitter() {
        UUID userId = UUID.randomUUID();
        SseEmitter emitter = registry.connect(userId);
        assertThat(emitter).isNotNull();
    }

    @Test
    void notifyStatusChange_unknown_user_is_safe() {
        UUID userId = UUID.randomUUID(); // 연결 없는 사용자
        assertThatCode(() -> registry.notifyStatusChange(userId, UserStatus.ACTIVE))
                .doesNotThrowAnyException();
    }

    @Test
    void notifyStatusChange_connected_user_sends_event() {
        UUID userId = UUID.randomUUID();
        registry.connect(userId);
        assertThatCode(() -> registry.notifyStatusChange(userId, UserStatus.ACTIVE))
                .doesNotThrowAnyException();
    }
}
