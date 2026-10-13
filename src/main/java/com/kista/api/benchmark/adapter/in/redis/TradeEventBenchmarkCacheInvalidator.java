package com.kista.api.benchmark.adapter.in.redis;

import com.kista.api.benchmark.application.usecase.BenchmarkUseCase;
import com.kista.shared.contract.notify.TradeEventEnvelope;
import com.kista.shared.platform.redis.RedisPubSubConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

// trading-core가 체결마다 발행하는 Redis "trade.event"를 notify SSE 구독자와 별도로 구독해 그 사용자의 벤치마크 비교 캐시를 비운다.
// Pub/Sub은 유실을 허용한다 — 놓친 경우 10분 TTL 만료로 수렴
@Slf4j
@Component
@RequiredArgsConstructor
class TradeEventBenchmarkCacheInvalidator implements MessageListener {

    private final RedisMessageListenerContainer listenerContainer;
    private final BenchmarkUseCase benchmarkUseCase;
    private final ObjectMapper objectMapper;

    @PostConstruct
    void subscribe() {
        listenerContainer.addMessageListener(this, new ChannelTopic(RedisPubSubConfig.TRADE_EVENT_CHANNEL));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            TradeEventEnvelope payload = objectMapper.readValue(message.getBody(), TradeEventEnvelope.class);
            benchmarkUseCase.invalidateUserCache(payload.userId());
        } catch (Exception e) {
            log.error("trade.event 벤치마크 캐시 무효화 실패", e);
        }
    }
}
