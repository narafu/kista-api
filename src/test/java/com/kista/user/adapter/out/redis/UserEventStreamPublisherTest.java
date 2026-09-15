package com.kista.user.adapter.out.redis;

import com.kista.platform.redis.RedisStreamConfig;
import com.kista.sharedkernel.UserDeletedEvent;
import com.kista.sharedkernel.UserNotifyProfileChangedEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 로컬 Redis(localhost:6379) 필요 — docker compose up -d redis 선행.
@Tag("integration")
@DisplayName("UserEventStreamPublisher XADD 발행 통합 테스트")
class UserEventStreamPublisherTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    @BeforeAll
    static void connectRedis() {
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterEach
    void cleanStreams() {
        redisTemplate.delete(RedisStreamConfig.USER_DELETED_STREAM);
        redisTemplate.delete(RedisStreamConfig.USER_NOTIFY_PROFILE_CHANGED_STREAM);
    }

    @AfterAll
    static void disconnectRedis() {
        connectionFactory.destroy();
    }

    @Test
    void onUserDeleted_addsRecordToStream() {
        UserEventStreamPublisher publisher = new UserEventStreamPublisher(redisTemplate, new ObjectMapper());
        UUID userId = UUID.randomUUID();

        publisher.onUserDeleted(new UserDeletedEvent(userId));

        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .read(StreamOffset.create(RedisStreamConfig.USER_DELETED_STREAM, ReadOffset.from("0")));
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getValue().get("payload").toString()).contains(userId.toString());
    }

    @Test
    void onProfileChanged_addsRecordToStream() {
        UserEventStreamPublisher publisher = new UserEventStreamPublisher(redisTemplate, new ObjectMapper());
        UUID userId = UUID.randomUUID();

        publisher.onProfileChanged(new UserNotifyProfileChangedEvent(
                userId, Map.of(), false, true, null, null));

        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .read(StreamOffset.create(RedisStreamConfig.USER_NOTIFY_PROFILE_CHANGED_STREAM, ReadOffset.from("0")));
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getValue().get("payload").toString()).contains(userId.toString());
    }
}
