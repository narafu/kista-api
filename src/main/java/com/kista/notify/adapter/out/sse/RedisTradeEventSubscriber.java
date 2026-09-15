package com.kista.notify.adapter.out.sse;

import com.kista.platform.redis.RedisPubSubConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.UUID;

// trading-core가 Redis "trade.event" 채널로 발행한 체결 알림을 구독해 root SSE 레지스트리로 전달한다.
// trading-core는 자기 own-type TradeEventView(com.kista.trading.notify.domain.model)로 발행하지만,
// 필드 shape이 root TradeEventView와 byte-identical이라 Jackson이 그대로 root 타입으로 역직렬화한다 —
// 별도 매핑 계층 없이 필드명 일치만으로 재구성(레코드라 순서·이름 일치 필요, 두 타입 모두 own-type 게이트
// 문서에 등재된 동일 shape 복제본).
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisTradeEventSubscriber implements MessageListener {

    private final RedisMessageListenerContainer listenerContainer;
    private final TradeSseEmitterRegistry tradeSseEmitterRegistry;
    private final ObjectMapper objectMapper;

    @PostConstruct
    void subscribe() {
        listenerContainer.addMessageListener(this, new ChannelTopic(RedisPubSubConfig.TRADE_EVENT_CHANNEL));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            TradeEventPayload payload = objectMapper.readValue(message.getBody(), TradeEventPayload.class);
            tradeSseEmitterRegistry.send(payload.userId(), payload.event());
        } catch (Exception e) {
            log.error("trade.event 역직렬화 실패", e);
        }
    }

    // Redis 발행 payload 역직렬화 대상 — trading-core RedisTradeEventPublisher.TradeEventPayload와 필드 shape 동일
    private record TradeEventPayload(UUID userId, com.kista.notify.domain.model.TradeEventView event) {
    }
}
