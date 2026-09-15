package com.kista.notify.adapter.out.sse;

import tools.jackson.databind.ObjectMapper;
import com.kista.notify.domain.model.TradeEventView;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

// 실제 로컬 Redis(localhost:6379) 필요 — docker compose up -d redis 선행. trading-core
// RedisTradeEventPublisher가 발행하는 것과 동일한 payload shape을 직접 publish해, 구독 측이
// TradeSseEmitterRegistry.send()를 올바른 인자로 호출하는지 왕복 검증한다(TossRedisTokenStoreIT 패턴).
@Tag("integration")
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisTradeEventSubscriber trade.event 구독 통합 테스트")
class RedisTradeEventSubscriberTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static RedisMessageListenerContainer listenerContainer;

    @Mock
    TradeSseEmitterRegistry tradeSseEmitterRegistry;

    @BeforeAll
    static void connectRedis() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", 6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        listenerContainer = new RedisMessageListenerContainer();
        listenerContainer.setConnectionFactory(connectionFactory);
        listenerContainer.afterPropertiesSet();
        listenerContainer.start();
    }

    @AfterAll
    static void disconnectRedis() throws Exception {
        listenerContainer.stop();
        listenerContainer.destroy();
        connectionFactory.destroy();
    }

    @Test
    void onMessage_deserializesPayloadAndForwardsToSseRegistry() {
        ObjectMapper objectMapper = new ObjectMapper();
        RedisTradeEventSubscriber subscriber =
                new RedisTradeEventSubscriber(listenerContainer, tradeSseEmitterRegistry, objectMapper);
        subscriber.subscribe();

        // 리스너 등록이 비동기라 발행 전 잠깐의 여유가 필요 — 실패 시 sleep 연장 대신 원인 재확인
        UUID userId = UUID.randomUUID();
        TradeEventView event = TradeEventView.sell("TQQQ", 3, 45.0, 135.0, "테스트계좌");
        String payload = "{\"userId\":\"" + userId + "\",\"event\":"
                + objectMapper.writeValueAsString(event) + "}";
        await();
        redisTemplate.convertAndSend("trade.event", payload);

        verify(tradeSseEmitterRegistry, timeout(3000)).send(eq(userId), eq(event));
    }

    private static void await() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
