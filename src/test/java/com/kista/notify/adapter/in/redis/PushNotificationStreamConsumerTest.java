package com.kista.notify.adapter.in.redis;

import com.kista.notify.application.port.output.PushNotificationPort;
import com.kista.platform.redis.RedisStreamConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushNotificationStreamConsumerTest {

    @Mock RedisConnectionFactory connectionFactory;
    @Mock StringRedisTemplate redisTemplate;
    @Mock StreamOperations<String, Object, Object> streamOperations;
    @Mock PushNotificationPort pushNotificationPort;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private PushNotificationStreamConsumer consumer() {
        when(redisTemplate.opsForStream()).thenReturn((StreamOperations) streamOperations);
        return new PushNotificationStreamConsumer(connectionFactory, redisTemplate, pushNotificationPort, new ObjectMapper());
    }

    private static MapRecord<String, String, String> record(UUID userId) {
        return record("{\"userId\":\"" + userId + "\",\"title\":\"체결\",\"body\":\"SOXL 매수 체결\"}");
    }

    private static MapRecord<String, String, String> record(String payload) {
        return StreamRecords.newRecord()
                .in(RedisStreamConfig.PUSH_NOTIFICATION_STREAM)
                .withId(RecordId.of("1-0"))
                .ofMap(Map.of("payload", payload));
    }

    @Test
    void 봉투를_풀어_포트에_위임하고_ack한다() {
        UUID userId = UUID.randomUUID();
        PushNotificationStreamConsumer consumer = consumer();
        MapRecord<String, String, String> record = record(userId);

        consumer.handle(record);

        verify(pushNotificationPort).pushIfEnabled(userId, "체결", "SOXL 매수 체결");
        verify(streamOperations).acknowledge(RedisStreamConfig.PUSH_NOTIFICATION_STREAM, RedisStreamConfig.ROOT_CONSUMER_GROUP, record.getId());
    }

    // 포트 예외도 삼키고 ack — 영구 재시도 방지
    @Test
    void 포트가_예외를_던져도_ack한다() {
        UUID userId = UUID.randomUUID();
        doThrow(new IllegalStateException("fcm down")).when(pushNotificationPort).pushIfEnabled(userId, "체결", "SOXL 매수 체결");
        PushNotificationStreamConsumer consumer = consumer();
        MapRecord<String, String, String> record = record(userId);

        consumer.handle(record);

        verify(streamOperations).acknowledge(RedisStreamConfig.PUSH_NOTIFICATION_STREAM, RedisStreamConfig.ROOT_CONSUMER_GROUP, record.getId());
    }

    @Test
    void 역직렬화_불가한_poison_메시지는_보내지_않고_ack한다() {
        PushNotificationStreamConsumer consumer = consumer();
        MapRecord<String, String, String> record = record("not-json");

        consumer.handle(record);

        verifyNoInteractions(pushNotificationPort);
        verify(streamOperations).acknowledge(RedisStreamConfig.PUSH_NOTIFICATION_STREAM, RedisStreamConfig.ROOT_CONSUMER_GROUP, record.getId());
    }
}
