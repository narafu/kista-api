package com.kista.notify.adapter.out.sse;

import com.kista.contract.notify.TradeEventEnvelope;
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

// trading-core가 Redis "trade.event" 채널로 발행한 체결 알림을 구독해 root SSE 레지스트리로 전달한다.
// 발행(trading-core RedisTradeEventPublisher)과 구독이 같은 contract 타입(TradeEventEnvelope/TradeEventMessage)을
// 공유하므로 별도 매핑 없이 그대로 역직렬화한다.
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
            TradeEventEnvelope payload = objectMapper.readValue(message.getBody(), TradeEventEnvelope.class);
            tradeSseEmitterRegistry.send(payload.userId(), payload.event());
        } catch (Exception e) {
            log.error("trade.event 역직렬화 실패", e);
        }
    }
}
