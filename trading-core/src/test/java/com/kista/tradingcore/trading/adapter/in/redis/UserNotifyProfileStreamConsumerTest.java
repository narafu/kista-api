package com.kista.tradingcore.trading.adapter.in.redis;

import com.kista.tradingcore.trading.application.port.output.TradingErrorReportPort;
import com.kista.shared.platform.redis.RedisStreamConfig;
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
    @Mock TradingErrorReportPort errorReportPort;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private UserNotifyProfileStreamConsumer consumer() {
        lenient().when(redisTemplate.opsForStream()).thenReturn((StreamOperations) streamOperations);
        return new UserNotifyProfileStreamConsumer(connectionFactory, redisTemplate, bridge, errorReportPort);
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

    // 옛 스냅샷 재적용은 occurredAt 버전 가드가 막으므로 일시 오류는 ack하지 않고 pending으로 남겨 재시도한다
    @Test
    void 일시_오류는_ack하지_않고_pending으로_남긴다() {
        UserNotifyProfileStreamConsumer consumer = consumer();
        MapRecord<String, String, String> record = record();
        doThrow(new IllegalStateException("db down")).when(bridge).handleProfileChangedRecord(record);

        consumer.handle(record);

        verifyNoInteractions(streamOperations);
    }
}
