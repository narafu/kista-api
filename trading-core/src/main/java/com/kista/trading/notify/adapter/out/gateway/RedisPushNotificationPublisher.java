package com.kista.trading.notify.adapter.out.gateway;

import com.kista.platform.redis.RedisPubSubConfig;
import com.kista.sharedkernel.UserPushNotificationRequestedEvent;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// FCM 발송 위임 — fcm_device_tokens가 users FK라 trading-core가 직접 조회할 수 없어 root에 Redis로 위임.
// fire-and-forget — 재시도 없이 최선노력, 유실 시 알림 1건 누락 정도.
@Component
@RequiredArgsConstructor
class RedisPushNotificationPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    void publish(UserPushNotificationRequestedEvent event) {
        String payload = objectMapper.writeValueAsString(event);
        redisTemplate.convertAndSend(RedisPubSubConfig.PUSH_NOTIFICATION_CHANNEL, payload);
    }
}
