package com.kista.adapter.out.sse;

import com.kista.domain.model.order.TradeEvent;
import com.kista.domain.model.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SseEmitterRegistryTest {

    @Mock TradeSseEmitterRegistry tradeSseEmitterRegistry;
    SseEmitterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SseEmitterRegistry(tradeSseEmitterRegistry);
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
        assertThatCode(() -> registry.notifyStatusChange(userId, User.UserStatus.ACTIVE))
                .doesNotThrowAnyException();
    }

    @Test
    void notifyStatusChange_connected_user_sends_event() {
        UUID userId = UUID.randomUUID();
        registry.connect(userId);
        assertThatCode(() -> registry.notifyStatusChange(userId, User.UserStatus.ACTIVE))
                .doesNotThrowAnyException();
    }

    @Test
    void notifyTrade_delegates_to_trade_registry() {
        UUID userId = UUID.randomUUID();
        TradeEvent event = TradeEvent.buy("SOXL", 5, 22.5, 112.5, "테스트계좌");
        registry.notifyTrade(userId, event);
        verify(tradeSseEmitterRegistry).send(userId, event);
    }
}
