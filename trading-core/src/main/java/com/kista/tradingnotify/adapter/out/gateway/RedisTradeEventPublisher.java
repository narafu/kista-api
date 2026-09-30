package com.kista.tradingnotify.adapter.out.gateway;

import com.kista.contract.notify.TradeEventEnvelope;
import com.kista.contract.notify.TradeEventMessage;
import com.kista.platform.redis.RedisPubSubConfig;
import com.kista.tradingnotify.application.port.output.TradingRealtimeNotificationPort;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

// fire-and-forget — SSE 유실은 UI 일시 끊김 정도라 Redis Stream(내구성)이 아니라 Pub/Sub 사용.
@Component
@RequiredArgsConstructor
class RedisTradeEventPublisher implements TradingRealtimeNotificationPort {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void notifyTrade(UUID userId, TradeEventMessage event) {
        String payload = objectMapper.writeValueAsString(new TradeEventEnvelope(userId, event));
        redisTemplate.convertAndSend(RedisPubSubConfig.TRADE_EVENT_CHANNEL, payload);
    }
}
