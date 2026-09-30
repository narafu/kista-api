package com.kista.tradingnotify.adapter.out.gateway;

import com.kista.platform.redis.RedisStreamConfig;
import com.kista.platform.redis.RedisStreams;
import com.kista.sharedkernel.UserPushNotificationRequestedEvent;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// FCM 발송 위임 — fcm_device_tokens가 users FK라 trading-core가 직접 조회할 수 없어 root에 Redis Stream으로 위임.
// 내구성 push — stream:user.push-notification.requested에 XADD하면 root 컨슈머 그룹 root가 발송 후 ack하고,
// root가 내려가 있어도 기동 후 이어서 소비한다(체결 푸시 1건 누락은 사용자 관점 사고라 옛 Pub/Sub 유실 허용을 폐기).
// 보존 상한은 MAXLEN 10,000건(RedisStreamConfig.PUSH_NOTIFICATION_MAXLEN) — 그 이내에서만 유실 없음.
@Component
@RequiredArgsConstructor
class RedisPushNotificationPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    void publish(UserPushNotificationRequestedEvent event) {
        String payload = objectMapper.writeValueAsString(event);
        RedisStreams.add(redisTemplate, RedisStreamConfig.PUSH_NOTIFICATION_STREAM, payload,
                RedisStreamConfig.PUSH_NOTIFICATION_MAXLEN);
    }
}
