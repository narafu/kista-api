package com.kista.trading.notify.adapter.out.gateway;

import tools.jackson.databind.ObjectMapper;
import com.kista.trading.notify.domain.model.TradeEventView;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 로컬 Redis(localhost:6379) 필요 — docker compose up -d redis 선행. RedisTradeEventPublisher가
// 발행한 payload를 StringRedisTemplate으로 직접 subscribe해 JSON 구조를 검증한다(TossRedisTokenStoreIT 패턴).
@Tag("integration")
@DisplayName("RedisTradeEventPublisher trade.event 발행 통합 테스트")
class RedisTradeEventPublisherTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static RedisMessageListenerContainer listenerContainer;

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
    void notifyTrade_publishesJsonPayloadToTradeEventChannel() throws InterruptedException {
        BlockingQueue<String> received = new ArrayBlockingQueue<>(1);
        MessageListenerAdapter adapter = new MessageListenerAdapter(
                (org.springframework.data.redis.connection.MessageListener) (message, pattern) ->
                        received.offer(new String(message.getBody())));
        listenerContainer.addMessageListener(adapter, new ChannelTopic("trade.event"));

        RedisTradeEventPublisher publisher = new RedisTradeEventPublisher(redisTemplate, new ObjectMapper());
        UUID userId = UUID.randomUUID();
        TradeEventView event = TradeEventView.buy("SOXL", 5, 22.5, 112.5, "테스트계좌");

        // 리스너 등록이 비동기라 발행 전 잠깐의 여유가 필요 — 실패 시 sleep 연장 대신 원인 재확인
        Thread.sleep(200);
        publisher.notifyTrade(userId, event);

        String payload = received.poll(3, TimeUnit.SECONDS);
        assertThat(payload).isNotNull();
        assertThat(payload).contains(userId.toString());
        assertThat(payload).contains("\"ticker\":\"SOXL\"");
        assertThat(payload).contains("\"kind\":\"BUY\"");

        listenerContainer.removeMessageListener(adapter);
    }
}
