package com.kista.api.notify.adapter.in.redis;

import com.kista.api.notify.application.port.output.PushNotificationPort;
import com.kista.shared.platform.redis.RedisStreamConfig;
import com.kista.shared.platform.redis.RedisStreamSubscriber;
import com.kista.shared.platform.redis.RedisStreams;
import com.kista.shared.kernel.UserPushNotificationRequestedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.TimeUnit;

// trading-core가 stream:user.push-notification.requested로 위임한 FCM 발송 요청(체결 푸시 등)을 받아
// PushNotificationPort로 전달하는 구독자 — trading-core와 root는 별도 프로세스라 ApplicationEventPublisher로는 전달이 불가능하다.
// 내구성 Stream(컨슈머 그룹 root가 ack)이라 root가 잠시 죽어 있어도 기동 후 이어서 받는다
// (단 발행 측 MAXLEN RedisStreamConfig.PUSH_NOTIFICATION_MAXLEN 이내까지만 보존 — 옛 Pub/Sub은 유실 허용이었다).
// 구독·ack·재기동·XCLAIM 복구 골격은 platform RedisStreamSubscriber가 맡는다.
@Component
public class PushNotificationStreamConsumer extends RedisStreamSubscriber {

    private final PushNotificationPort pushNotificationPort; // 사용자 조회·FCM 채널 판정·발송의 단일 진입점
    private final ObjectMapper objectMapper;                 // 봉투 payload 역직렬화

    public PushNotificationStreamConsumer(RedisConnectionFactory connectionFactory, StringRedisTemplate redisTemplate,
                                          PushNotificationPort pushNotificationPort, ObjectMapper objectMapper) {
        super(connectionFactory, redisTemplate, RedisStreamConfig.PUSH_NOTIFICATION_STREAM,
                RedisStreamConfig.ROOT_CONSUMER_GROUP, "root-push");
        this.pushNotificationPort = pushNotificationPort;
        this.objectMapper = objectMapper;
    }

    // 기동 완료 후 구독 시작 — @PostConstruct면 메서드 리스너 등록 전에 밀린 메시지를 발행해 이벤트가 유실되고 Modulith 리스너 캐시가 비어 EPR 기록이 멈춘다
    @EventListener(ApplicationReadyEvent.class)
    void init() {
        start();
    }

    // 봉투 payload → UserPushNotificationRequestedEvent → 포트가 채널 판정 후 발송.
    // 역직렬화 실패는 예외로 던져 베이스가 warn 후 ack한다(poison 메시지의 영구 재시도 방지)
    @Override
    protected void onRecord(MapRecord<String, String, String> record) {
        UserPushNotificationRequestedEvent event =
                objectMapper.readValue(RedisStreams.payload(record), UserPushNotificationRequestedEvent.class);
        pushNotificationPort.pushIfEnabled(event.userId(), event.title(), event.body());
    }

    // 5분마다 끊긴 구독 재기동 + pending 복구
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    void scheduledRecovery() {
        recoverPending();
    }
}
