package com.kista.trading.notify.adapter.out.gateway;

import com.kista.platform.redis.RedisPubSubConfig;
import com.kista.trading.notify.application.port.output.TradingRealtimeNotificationPort;
import com.kista.trading.notify.domain.model.TradeEventView;
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
    public void notifyTrade(UUID userId, TradeEventView event) {
        String payload = objectMapper.writeValueAsString(new TradeEventPayload(userId, event));
        redisTemplate.convertAndSend(RedisPubSubConfig.TRADE_EVENT_CHANNEL, payload);
    }

    // Redis 발행 payload — userId + trading-core own-type TradeEventView 그대로 직렬화
    private record TradeEventPayload(UUID userId, TradeEventView event) {
    }
}
