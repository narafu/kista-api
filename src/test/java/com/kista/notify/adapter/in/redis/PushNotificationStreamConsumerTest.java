package com.kista.notify.adapter.in.redis;

import com.kista.notify.adapter.out.gateway.FcmAdapter;
import com.kista.platform.redis.RedisStreamConfig;
import com.kista.sharedkernel.NotificationChannel;
import com.kista.support.DomainFixtures;
import com.kista.user.application.port.output.UserPort;
import com.kista.user.domain.model.User;
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
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushNotificationStreamConsumerTest {

    @Mock RedisConnectionFactory connectionFactory;
    @Mock StringRedisTemplate redisTemplate;
    @Mock StreamOperations<String, Object, Object> streamOperations;
    @Mock FcmAdapter fcmAdapter;
    @Mock UserPort userPort;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private PushNotificationStreamConsumer consumer() {
        when(redisTemplate.opsForStream()).thenReturn((StreamOperations) streamOperations);
        return new PushNotificationStreamConsumer(connectionFactory, redisTemplate, fcmAdapter, userPort, new ObjectMapper());
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
    void FCM_채널_사용자에게_푸시를_보내고_ack한다() {
        User user = DomainFixtures.activeUser(UUID.randomUUID(), NotificationChannel.FCM);
        when(userPort.findByIdOrThrow(user.id())).thenReturn(user);
        PushNotificationStreamConsumer consumer = consumer();
        MapRecord<String, String, String> record = record(user.id());

        consumer.handle(record);

        verify(fcmAdapter).send(user.id(), "체결", "SOXL 매수 체결");
        verify(streamOperations).acknowledge(RedisStreamConfig.PUSH_NOTIFICATION_STREAM, RedisStreamConfig.ROOT_CONSUMER_GROUP, record.getId());
    }

    @Test
    void FCM을_포함하지_않는_채널이면_보내지_않고_ack한다() {
        User user = DomainFixtures.activeUser(UUID.randomUUID(), NotificationChannel.TELEGRAM);
        when(userPort.findByIdOrThrow(user.id())).thenReturn(user);
        PushNotificationStreamConsumer consumer = consumer();
        MapRecord<String, String, String> record = record(user.id());

        consumer.handle(record);

        verify(fcmAdapter, never()).send(any(), any(), any());
        verify(streamOperations).acknowledge(RedisStreamConfig.PUSH_NOTIFICATION_STREAM, RedisStreamConfig.ROOT_CONSUMER_GROUP, record.getId());
    }

    // 사용자 조회 실패도 처리 실패로 삼키고 ack — 영구 재시도 방지
    @Test
    void 사용자를_찾을_수_없어도_ack한다() {
        UUID userId = UUID.randomUUID();
        when(userPort.findByIdOrThrow(userId)).thenThrow(new NoSuchElementException("없음"));
        PushNotificationStreamConsumer consumer = consumer();
        MapRecord<String, String, String> record = record(userId);

        consumer.handle(record);

        verifyNoInteractions(fcmAdapter);
        verify(streamOperations).acknowledge(RedisStreamConfig.PUSH_NOTIFICATION_STREAM, RedisStreamConfig.ROOT_CONSUMER_GROUP, record.getId());
    }

    @Test
    void 역직렬화_불가한_poison_메시지는_보내지_않고_ack한다() {
        PushNotificationStreamConsumer consumer = consumer();
        MapRecord<String, String, String> record = record("not-json");

        consumer.handle(record);

        verifyNoInteractions(userPort, fcmAdapter);
        verify(streamOperations).acknowledge(RedisStreamConfig.PUSH_NOTIFICATION_STREAM, RedisStreamConfig.ROOT_CONSUMER_GROUP, record.getId());
    }
}
