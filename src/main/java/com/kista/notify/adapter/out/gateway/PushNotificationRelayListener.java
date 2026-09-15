package com.kista.notify.adapter.out.gateway;

import com.kista.platform.redis.RedisPubSubConfig;
import com.kista.sharedkernel.UserPushNotificationRequestedEvent;
import com.kista.user.application.port.output.UserPort;
import com.kista.user.domain.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

// trading-core가 Redis "user.push-notification.requested" 채널로 위임한 FCM 발송 요청을 구독해
// 기존 FcmAdapter로 전달한다 — trading-core와 root는 별도 프로세스라 ApplicationEventPublisher.publishEvent로는
// 전달이 불가능했던 것을 Redis Pub/Sub 구독으로 교체.
@Slf4j
@Component
@RequiredArgsConstructor
class PushNotificationRelayListener implements MessageListener {

    private final RedisMessageListenerContainer listenerContainer;
    private final FcmAdapter fcmAdapter;
    private final UserPort userPort;
    private final ObjectMapper objectMapper;

    @PostConstruct
    void subscribe() {
        listenerContainer.addMessageListener(this, new ChannelTopic(RedisPubSubConfig.PUSH_NOTIFICATION_CHANNEL));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            UserPushNotificationRequestedEvent event =
                    objectMapper.readValue(message.getBody(), UserPushNotificationRequestedEvent.class);
            User user = userPort.findByIdOrThrow(event.userId());
            if (user.notificationChannel().includesFcm()) {
                fcmAdapter.send(event.userId(), event.title(), event.body());
            }
        } catch (Exception e) {
            log.error("user.push-notification.requested 역직렬화/처리 실패", e);
        }
    }
}
