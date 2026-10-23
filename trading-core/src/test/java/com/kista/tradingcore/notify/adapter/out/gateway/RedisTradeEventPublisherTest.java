package com.kista.tradingcore.notify.adapter.out.gateway;

import com.kista.shared.kernel.Currency;
import java.math.BigDecimal;
import tools.jackson.databind.ObjectMapper;
import com.kista.shared.contract.notify.TradeEventMessage;
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
        UUID userId = UUID.randomUUID();
        BlockingQueue<String> received = new ArrayBlockingQueue<>(1);
        // Pub/Sub 채널은 DB 구분이 없다 — 로컬 trading-core가 같은 채널에 실제 매매 이벤트를 발행할 수 있어 이 테스트 userId만 받는다
        MessageListenerAdapter adapter = new MessageListenerAdapter(
                (org.springframework.data.redis.connection.MessageListener) (message, pattern) -> {
                    String body = new String(message.getBody());
                    if (body.contains(userId.toString())) received.offer(body);
                });
        listenerContainer.addMessageListener(adapter, new ChannelTopic("trade.event"));

        RedisTradeEventPublisher publisher = new RedisTradeEventPublisher(redisTemplate, new ObjectMapper());
        TradeEventMessage event = TradeEventMessage.buy("SOXL", BigDecimal.valueOf(5), 22.5, 112.5, "테스트계좌", UUID.randomUUID(), UUID.randomUUID(), Currency.USD);

        // 리스너 등록이 비동기라 발행 전 잠깐의 여유가 필요 — 실패 시 sleep 연장 대신 원인 재확인
        Thread.sleep(200);
        publisher.notifyTrade(userId, event);

        String payload = received.poll(3, TimeUnit.SECONDS);
        assertThat(payload).isNotNull(); // 리스너가 이 userId만 받으므로 수신 자체가 userId 포함 검증
        assertThat(payload).contains("\"ticker\":\"SOXL\"");
        assertThat(payload).contains("\"kind\":\"BUY\"");

        listenerContainer.removeMessageListener(adapter);
    }
}
