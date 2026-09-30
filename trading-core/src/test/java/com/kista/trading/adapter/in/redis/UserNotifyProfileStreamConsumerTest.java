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

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserNotifyProfileStreamConsumerTest {

    @Mock RedisConnectionFactory connectionFactory;
    @Mock StringRedisTemplate redisTemplate;
    @Mock StreamOperations<String, Object, Object> streamOperations;
    @Mock UserEventStreamBridge bridge;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private UserNotifyProfileStreamConsumer consumer() {
        lenient().when(redisTemplate.opsForStream()).thenReturn((StreamOperations) streamOperations);
        return new UserNotifyProfileStreamConsumer(connectionFactory, redisTemplate, bridge);
    }

    private static MapRecord<String, String, String> record() {
        return StreamRecords.newRecord()
                .in(RedisStreamConfig.USER_NOTIFY_PROFILE_CHANGED_STREAM)
                .withId(RecordId.of("1-0"))
                .ofMap(Map.of("payload", "{}"));
    }

    @Test
    void 브릿지에_위임하고_ack한다() {
        UserNotifyProfileStreamConsumer consumer = consumer();
        MapRecord<String, String, String> record = record();

        consumer.handle(record);

        verify(bridge).handleProfileChangedRecord(record);
        verify(streamOperations).acknowledge(RedisStreamConfig.USER_NOTIFY_PROFILE_CHANGED_STREAM, RedisStreamConfig.TRADING_CONSUMER_GROUP, record.getId());
    }

    // 역직렬화 실패(JacksonException)는 poison — 재시도해도 같은 결과라 ack로 버린다
    @Test
    void 역직렬화_실패는_poison이라_ack한다() {
        UserNotifyProfileStreamConsumer consumer = consumer();
        MapRecord<String, String, String> record = record();
        doThrow(new StreamReadException("잘못된 JSON")).when(bridge).handleProfileChangedRecord(record);

        consumer.handle(record);

        verify(streamOperations).acknowledge(RedisStreamConfig.USER_NOTIFY_PROFILE_CHANGED_STREAM, RedisStreamConfig.TRADING_CONSUMER_GROUP, record.getId());
    }

    // last-write upsert 소비자라 stale 재적용을 막기 위해 일시 오류도 ack한다(베이스 기본 정책) — 누락은 다음 설정 변경이 바로잡는다
    @Test
    void 일시_오류도_ack해_stale_재적용을_막는다() {
        UserNotifyProfileStreamConsumer consumer = consumer();
        MapRecord<String, String, String> record = record();
        doThrow(new IllegalStateException("db down")).when(bridge).handleProfileChangedRecord(record);

        consumer.handle(record);

        verify(streamOperations).acknowledge(RedisStreamConfig.USER_NOTIFY_PROFILE_CHANGED_STREAM, RedisStreamConfig.TRADING_CONSUMER_GROUP, record.getId());
    }
}
