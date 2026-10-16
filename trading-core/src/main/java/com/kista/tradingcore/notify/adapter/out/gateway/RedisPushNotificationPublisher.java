package com.kista.tradingcore.notify.adapter.out.gateway;

import com.kista.shared.platform.redis.RedisStreamConfig;
import com.kista.shared.platform.redis.RedisStreams;
import com.kista.shared.kernel.UserPushNotificationRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// FCM 발송 위임 — fcm_device_tokens가 users FK라 trading-core가 직접 조회할 수 없어 root에 Redis Stream으로 위임.
// 내구성 push — stream:user.push-notification.requested에 XADD하면 root 컨슈머 그룹 root가 발송 후 ack하고,
// root가 내려가 있어도 기동 후 이어서 소비한다(체결 푸시 1건 누락은 사용자 관점 사고라 옛 Pub/Sub 유실 허용을 폐기).
// 보존 상한은 MAXLEN 10,000건(RedisStreamConfig.PUSH_NOTIFICATION_MAXLEN) — 그 이내에서만 유실 없음.
@Slf4j
@Component
@RequiredArgsConstructor
class RedisPushNotificationPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // XADD 실패는 삼킨다 — 알림 리스너는 트랜잭션 밖 발행 지점(CyclePositionPersistor 등)에서 인라인 실행되므로 예외가 매매 흐름(사이클 재등록 등)을 끊는다.
    // 한계: Redis 장애 순간의 푸시는 유실(텔레그램 발송 실패와 같은 정책), 재시도가 필요해지면 로컬 outbox로
    void publish(UserPushNotificationRequestedEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            RedisStreams.add(redisTemplate, RedisStreamConfig.PUSH_NOTIFICATION_STREAM, payload,
                    RedisStreamConfig.PUSH_NOTIFICATION_MAXLEN);
        } catch (Exception e) {
            log.error("[userId={}] 푸시 위임 XADD 실패 — 유실: {} ({})", event.userId(), event.title(), e.getMessage());
        }
    }
}
