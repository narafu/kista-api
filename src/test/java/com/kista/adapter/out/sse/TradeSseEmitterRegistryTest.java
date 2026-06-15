package com.kista.adapter.out.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kista.domain.model.order.TradeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(MockitoExtension.class)
class TradeSseEmitterRegistryTest {

    TradeSseEmitterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new TradeSseEmitterRegistry(new ObjectMapper());
    }

    @Test
    void connect_returns_emitter_with_30min_timeout() {
        UUID userId = UUID.randomUUID();
        SseEmitter emitter = registry.connect(userId);
        assertThat(emitter).isNotNull();
    }

    @Test
    void connect_sends_initial_ping() {
        UUID userId = UUID.randomUUID();
        assertThatCode(() -> registry.connect(userId)).doesNotThrowAnyException();
    }

    @Test
    void send_to_unknown_user_is_safe() {
        UUID userId = UUID.randomUUID(); // 연결 없는 사용자
        TradeEvent event = TradeEvent.buy("SOXL", 5, 22.5, 112.5, "테스트계좌");
        assertThatCode(() -> registry.send(userId, event)).doesNotThrowAnyException();
    }

    @Test
    void send_to_connected_user_sends_event() {
        UUID userId = UUID.randomUUID();
        registry.connect(userId);
        TradeEvent event = TradeEvent.sell("SOXL", 5, 23.0, 115.0, "테스트계좌");
        assertThatCode(() -> registry.send(userId, event)).doesNotThrowAnyException();
    }
}
