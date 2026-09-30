package com.kista.trading.adapter.in.redis;

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

import tools.jackson.core.exc.StreamReadException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDeletedStreamConsumerTest {

    @Mock RedisConnectionFactory connectionFactory;
    @Mock StringRedisTemplate redisTemplate;
    @Mock StreamOperations<String, Object, Object> streamOperations;
    @Mock UserEventStreamBridge bridge;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private UserDeletedStreamConsumer consumer() {
        lenient().when(redisTemplate.opsForStream()).thenReturn((StreamOperations) streamOperations);
        return new UserDeletedStreamConsumer(connectionFactory, redisTemplate, bridge);
    }

    private static MapRecord<String, String, String> record() {
        return StreamRecords.newRecord()
                .in(RedisStreamConfig.USER_DELETED_STREAM)
                .withId(RecordId.of("1-0"))
                .ofMap(Map.of("payload", "{}"));
    }

    @Test
    void 브릿지에_위임하고_ack한다() {
        UserDeletedStreamConsumer consumer = consumer();
        MapRecord<String, String, String> record = record();

        consumer.handle(record);

        verify(bridge).handleUserDeletedRecord(record);
        verify(streamOperations).acknowledge(RedisStreamConfig.USER_DELETED_STREAM, RedisStreamConfig.TRADING_CONSUMER_GROUP, record.getId());
    }

    // 역직렬화 실패(JacksonException)는 poison — 재시도해도 같은 결과라 ack로 버린다
    @Test
    void 역직렬화_실패는_poison이라_ack한다() {
        UserDeletedStreamConsumer consumer = consumer();
        MapRecord<String, String, String> record = record();
        doThrow(new StreamReadException("잘못된 JSON")).when(bridge).handleUserDeletedRecord(record);

        consumer.handle(record);

        verify(streamOperations).acknowledge(RedisStreamConfig.USER_DELETED_STREAM, RedisStreamConfig.TRADING_CONSUMER_GROUP, record.getId());
    }

    // payload 필드 누락 등 결정적 입력 오류(IAE/NPE)도 재시도해 봐야 같은 결과라 poison으로 ack
    @Test
    void 결정적_입력_오류도_poison이라_ack한다() {
        UserDeletedStreamConsumer consumer = consumer();
        MapRecord<String, String, String> record = record();
        doThrow(new IllegalArgumentException("payload 없음")).when(bridge).handleUserDeletedRecord(record);

        consumer.handle(record);

        verify(streamOperations).acknowledge(RedisStreamConfig.USER_DELETED_STREAM, RedisStreamConfig.TRADING_CONSUMER_GROUP, record.getId());
    }

    // 재발행·DB 일시 오류는 ack하지 않고 pending으로 남겨 XCLAIM 재시도에 맡긴다
    @Test
    void 일시_오류는_ack하지_않고_pending으로_남긴다() {
        UserDeletedStreamConsumer consumer = consumer();
        MapRecord<String, String, String> record = record();
        doThrow(new IllegalStateException("db down")).when(bridge).handleUserDeletedRecord(record);

        consumer.handle(record);

        verifyNoInteractions(streamOperations);
    }

    // 5분 주기 x 24회 = 약 2시간 재시도 후 포기
    @Test
    void 재시도_한도는_24회다() {
        assertThat(consumer().maxDeliveries()).isEqualTo(24);
    }
}
