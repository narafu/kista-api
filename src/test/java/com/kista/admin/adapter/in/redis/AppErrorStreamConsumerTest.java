package com.kista.admin.adapter.in.redis;

import com.kista.admin.application.port.output.AppErrorLogPort;
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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppErrorStreamConsumerTest {

    @Mock RedisConnectionFactory connectionFactory;
    @Mock StringRedisTemplate redisTemplate;
    @Mock StreamOperations<String, Object, Object> streamOperations;
    @Mock AppErrorLogPort appErrorLogPort;

    @Test
    @SuppressWarnings("unchecked")
    void handle_savesErrorLogAndAcks() {
        when(redisTemplate.opsForStream()).thenReturn((StreamOperations) streamOperations);
        AppErrorStreamConsumer consumer = new AppErrorStreamConsumer(connectionFactory, redisTemplate, new ObjectMapper(), appErrorLogPort);
        MapRecord<String, String, String> record = record("""
                {"errorType":"KisApiException","message":"연결 오류","stackTrace":"at foo()","context":{"caller":"TradingExceptionHandler"}}
                """);

        consumer.handle(record);

        verify(appErrorLogPort).save("KisApiException", "연결 오류", "at foo()", Map.of("caller", "TradingExceptionHandler"));
        verify(streamOperations).acknowledge(RedisStreamConfig.APP_ERROR_STREAM, RedisStreamConfig.ROOT_CONSUMER_GROUP, record.getId());
    }

    // 역직렬화 불가한 poison 메시지는 저장 없이 ack해 영구 재시도를 막는다
    @Test
    @SuppressWarnings("unchecked")
    void handle_malformedPayload_acksWithoutSaving() {
        when(redisTemplate.opsForStream()).thenReturn((StreamOperations) streamOperations);
        AppErrorStreamConsumer consumer = new AppErrorStreamConsumer(connectionFactory, redisTemplate, new ObjectMapper(), appErrorLogPort);
        MapRecord<String, String, String> record = record("not-json");

        consumer.handle(record);

        verifyNoInteractions(appErrorLogPort);
        verify(streamOperations).acknowledge(RedisStreamConfig.APP_ERROR_STREAM, RedisStreamConfig.ROOT_CONSUMER_GROUP, record.getId());
    }

    private static MapRecord<String, String, String> record(String payload) {
        return StreamRecords.newRecord()
                .in(RedisStreamConfig.APP_ERROR_STREAM)
                .withId(RecordId.of("1-0"))
                .ofMap(Map.of("payload", payload));
    }
}
