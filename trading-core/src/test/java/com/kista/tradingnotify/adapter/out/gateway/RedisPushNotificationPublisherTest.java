package com.kista.tradingnotify.adapter.out.gateway;

import com.kista.platform.redis.RedisStreamConfig;
import com.kista.sharedkernel.UserPushNotificationRequestedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisPushNotificationPublisherTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock StreamOperations<String, Object, Object> streamOperations;

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void publish_addsJsonPayloadToPushNotificationStream() {
        when(redisTemplate.opsForStream()).thenReturn((StreamOperations) streamOperations);
        RedisPushNotificationPublisher publisher = new RedisPushNotificationPublisher(redisTemplate, new ObjectMapper());
        UUID userId = UUID.randomUUID();

        publisher.publish(new UserPushNotificationRequestedEvent(userId, "장 개시", "🟢 미국 장이 열렸습니다."));

        ArgumentCaptor<MapRecord<String, Object, Object>> captor = ArgumentCaptor.forClass(MapRecord.class);
        ArgumentCaptor<XAddOptions> optionsCaptor = ArgumentCaptor.forClass(XAddOptions.class);
        verify(streamOperations).add(captor.capture(), optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getMaxlen()).isEqualTo(RedisStreamConfig.PUSH_NOTIFICATION_MAXLEN);
        assertThat(captor.getValue().getStream()).isEqualTo(RedisStreamConfig.PUSH_NOTIFICATION_STREAM);
        String payload = captor.getValue().getValue().get("payload").toString();
        assertThat(payload).contains(userId.toString()).contains("\"title\":\"장 개시\"");
    }
}
