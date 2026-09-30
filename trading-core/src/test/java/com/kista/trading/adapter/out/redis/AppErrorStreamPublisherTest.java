package com.kista.trading.adapter.out.redis;

import com.kista.platform.redis.RedisStreamConfig;
import com.kista.sharedkernel.AppErrorRaisedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppErrorStreamPublisherTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock StreamOperations<String, Object, Object> streamOperations;

    @Test
    @SuppressWarnings("unchecked")
    void onAppErrorRaised_addsJsonPayloadToAppErrorStream() {
        when(redisTemplate.opsForStream()).thenReturn((StreamOperations) streamOperations);
        AppErrorStreamPublisher publisher = new AppErrorStreamPublisher(redisTemplate, new ObjectMapper());

        publisher.onAppErrorRaised(new AppErrorRaisedEvent("KisApiException", "연결 오류", "at foo()",
                Map.of("caller", "TradingExceptionHandler")));

        ArgumentCaptor<MapRecord<String, Object, Object>> captor = ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOperations).add(captor.capture(), any(XAddOptions.class));
        assertThat(captor.getValue().getStream()).isEqualTo(RedisStreamConfig.APP_ERROR_STREAM);
        String payload = captor.getValue().getValue().get("payload").toString();
        assertThat(payload).contains("\"errorType\":\"KisApiException\"").contains("TradingExceptionHandler");
    }

    // Redis 장애로 XADD가 실패해도 오류 보고가 원래 요청 처리를 깨뜨리지 않는다
    @Test
    void onAppErrorRaised_redisFailure_isSwallowed() {
        when(redisTemplate.opsForStream()).thenThrow(new IllegalStateException("redis down"));
        AppErrorStreamPublisher publisher = new AppErrorStreamPublisher(redisTemplate, new ObjectMapper());

        assertThatCode(() -> publisher.onAppErrorRaised(new AppErrorRaisedEvent("X", "m", "s", Map.of())))
                .doesNotThrowAnyException();
    }
}
