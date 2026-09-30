package com.kista.notify.adapter.in.redis;

import com.kista.notify.application.port.output.PushNotificationPort;
import com.kista.platform.redis.RedisPubSubConfig;
import com.kista.sharedkernel.UserPushNotificationRequestedEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

// Stream 전환 호환용 — 옛 trading-core(Pub/Sub 발행)와 새 root가 공존하는 배포 창에서 체결 푸시를 받는다.
// 삭제 조건: trading-core 배포가 Stream 발행 버전으로 확인된 다음 릴리스에서 이 클래스와
// RedisPubSubConfig.LEGACY_PUSH_NOTIFICATION_CHANNEL을 함께 삭제한다(docs/reviews 후속 과제 참고).
// Pub/Sub은 유실 허용이라 내구성은 없다 — 발송 판정은 신규 경로와 같은 PushNotificationPort를 쓴다.
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressWarnings("deprecation")
class LegacyPushNotificationRelayListener implements MessageListener {

    private final RedisMessageListenerContainer listenerContainer; // 옛 Pub/Sub 채널 구독 등록
    private final PushNotificationPort pushNotificationPort;       // 사용자 조회·FCM 채널 판정·발송
    private final ObjectMapper objectMapper;                       // 메시지 본문 역직렬화

    // 기동 시 옛 채널 구독 등록
    @PostConstruct
    void subscribe() {
        listenerContainer.addMessageListener(this, new ChannelTopic(RedisPubSubConfig.LEGACY_PUSH_NOTIFICATION_CHANNEL));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            UserPushNotificationRequestedEvent event =
                    objectMapper.readValue(message.getBody(), UserPushNotificationRequestedEvent.class);
            pushNotificationPort.pushIfEnabled(event.userId(), event.title(), event.body());
        } catch (Exception e) {
            log.error("user.push-notification.requested(legacy Pub/Sub) 역직렬화/처리 실패", e);
        }
    }
}
