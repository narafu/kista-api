package com.kista.trading.adapter.in.redis;

import com.kista.platform.redis.RedisStreamConfig;
import com.kista.sharedkernel.UserDeletedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 로컬 Redis(localhost:6379) 필요 — docker compose up -d redis 선행.
// UserEventStreamBridge가 스트림 메시지를 실제로 로컬 이벤트로 재발행하는지만 검증하고,
// 기존 4개 cascade 리스너 자체의 동작(soft-delete 등)은 각 리스너의 기존 단위 테스트가 검증한다.
@Tag("integration")
@DisplayName("UserEventStreamBridge 스트림→로컬이벤트 재발행 통합 테스트")
class UserEventStreamBridgeIT {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        redisTemplate.delete(RedisStreamConfig.USER_DELETED_STREAM);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void handleUserDeletedRecord_republishesLocallyAndAcks() {
        ApplicationEventPublisher eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        UserEventStreamBridge bridge = new UserEventStreamBridge(redisTemplate, new ObjectMapper(), new UserEventRepublisher(eventPublisher));
        UUID userId = UUID.randomUUID();
        redisTemplate.<String, String>opsForStream().createGroup(RedisStreamConfig.USER_DELETED_STREAM,
                org.springframework.data.redis.connection.stream.ReadOffset.from("0"), RedisStreamConfig.TRADING_CONSUMER_GROUP);
        redisTemplate.<String, String>opsForStream().add(StreamRecords.newRecord()
                .in(RedisStreamConfig.USER_DELETED_STREAM)
                .ofMap(Collections.singletonMap("payload", "{\"userId\":\"" + userId + "\"}")));

        var records = redisTemplate.<String, String>opsForStream().read(
                org.springframework.data.redis.connection.stream.Consumer.from(RedisStreamConfig.TRADING_CONSUMER_GROUP, "test-consumer"),
                org.springframework.data.redis.connection.stream.StreamReadOptions.empty(),
                org.springframework.data.redis.connection.stream.StreamOffset.create(RedisStreamConfig.USER_DELETED_STREAM,
                        org.springframework.data.redis.connection.stream.ReadOffset.lastConsumed()));

        bridge.handleUserDeletedRecord(records.get(0));

        Mockito.verify(eventPublisher).publishEvent(new UserDeletedEvent(userId));
        var pending = redisTemplate.opsForStream().pending(RedisStreamConfig.USER_DELETED_STREAM, RedisStreamConfig.TRADING_CONSUMER_GROUP);
        assertThat(pending.getTotalPendingMessages()).isZero(); // ack 완료 확인
    }
}
