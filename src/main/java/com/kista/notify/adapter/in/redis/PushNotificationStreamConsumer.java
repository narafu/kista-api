package com.kista.notify.adapter.in.redis;

import com.kista.notify.adapter.out.gateway.FcmAdapter;
import com.kista.platform.redis.RedisStreamConfig;
import com.kista.platform.redis.RedisStreamSubscriber;
import com.kista.platform.redis.RedisStreams;
import com.kista.sharedkernel.UserPushNotificationRequestedEvent;
import com.kista.user.application.port.output.UserPort;
import com.kista.user.domain.model.User;
import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.TimeUnit;

// trading-core가 stream:user.push-notification.requested로 위임한 FCM 발송 요청(체결 푸시 등)을 받아
// FcmAdapter로 전달하는 구독자 — trading-core와 root는 별도 프로세스라 ApplicationEventPublisher로는 전달이 불가능하다.
// 내구성 Stream(컨슈머 그룹 root가 ack)이라 root가 잠시 죽어 있어도 기동 후 이어서 받는다(옛 Pub/Sub은 유실 허용이었다).
// 구독·ack·재기동·XCLAIM 복구 골격은 platform RedisStreamSubscriber가 맡는다.
@Component
public class PushNotificationStreamConsumer extends RedisStreamSubscriber {

    private final FcmAdapter fcmAdapter;   // FCM 발송 — 디바이스 토큰 조회·만료 토큰 정리 포함
    private final UserPort userPort;       // 수신자 알림 채널(FCM 포함 여부) 확인
    private final ObjectMapper objectMapper;

    public PushNotificationStreamConsumer(RedisConnectionFactory connectionFactory, StringRedisTemplate redisTemplate,
                                          FcmAdapter fcmAdapter, UserPort userPort, ObjectMapper objectMapper) {
        super(connectionFactory, redisTemplate, RedisStreamConfig.PUSH_NOTIFICATION_STREAM,
                RedisStreamConfig.ROOT_CONSUMER_GROUP, "root-push");
        this.fcmAdapter = fcmAdapter;
        this.userPort = userPort;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        start();
    }

    // 봉투 payload → UserPushNotificationRequestedEvent → 사용자 채널이 FCM을 포함하면 발송.
    // 역직렬화·조회 실패는 예외로 던져 베이스가 warn 후 ack한다(poison 메시지의 영구 재시도 방지)
    @Override
    protected void onRecord(MapRecord<String, String, String> record) {
        UserPushNotificationRequestedEvent event =
                objectMapper.readValue(RedisStreams.payload(record), UserPushNotificationRequestedEvent.class);
        User user = userPort.findByIdOrThrow(event.userId());
        if (user.notificationChannel().includesFcm()) {
            fcmAdapter.send(event.userId(), event.title(), event.body());
        }
    }

    // 5분마다 끊긴 구독 재기동 + pending 복구
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    void scheduledRecovery() {
        recoverPending();
    }
}
