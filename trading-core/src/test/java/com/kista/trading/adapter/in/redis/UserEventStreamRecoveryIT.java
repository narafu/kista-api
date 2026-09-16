package com.kista.trading.adapter.in.redis;

import com.kista.platform.redis.RedisStreamConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 로컬 Redis(localhost:6379) 필요. 컨슈머 A가 읽고 ack 안 한 채(크래시 시뮬레이션) 종료된
// pending 항목을, 신규 컨슈머 B가 claim해 재처리+ack하는 시나리오 검증.
@Tag("integration")
@DisplayName("UserEventStreamRecoveryScheduler pending claim 재처리 통합 테스트")
class UserEventStreamRecoveryIT {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        redisTemplate.delete(RedisStreamConfig.USER_DELETED_STREAM);
        redisTemplate.opsForStream().createGroup(RedisStreamConfig.USER_DELETED_STREAM, ReadOffset.from("0"), RedisStreamConfig.TRADING_CONSUMER_GROUP);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void reclaim_recoversPendingRecordAndAcks() {
        UUID userId = UUID.randomUUID();
        redisTemplate.opsForStream().add(StreamRecords.newRecord()
                .in(RedisStreamConfig.USER_DELETED_STREAM)
                .ofMap(Collections.singletonMap("payload", "{\"userId\":\"" + userId + "\"}")));
        // 죽은 컨슈머가 읽기만 하고 ack 안 함 — pending 상태로 남김
        redisTemplate.opsForStream().read(Consumer.from(RedisStreamConfig.TRADING_CONSUMER_GROUP, "dead-consumer"),
                StreamReadOptions.empty(), StreamOffset.create(RedisStreamConfig.USER_DELETED_STREAM, ReadOffset.lastConsumed()));

        ApplicationEventPublisher eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        UserEventRepublisher republisher = new UserEventRepublisher(eventPublisher);
        UserEventStreamBridge bridge = new UserEventStreamBridge(redisTemplate, new ObjectMapper(), republisher);
        UserEventStreamRecoveryScheduler scheduler = new UserEventStreamRecoveryScheduler(
                redisTemplate, bridge, Mockito.mock(com.kista.platform.scheduling.SchedulerJobRunner.class));

        scheduler.reclaimPending(Duration.ZERO); // 유휴시간 0으로 즉시 claim 대상 처리

        var pending = redisTemplate.opsForStream().pending(RedisStreamConfig.USER_DELETED_STREAM, RedisStreamConfig.TRADING_CONSUMER_GROUP);
        assertThat(pending.getTotalPendingMessages()).isZero();
    }
}
