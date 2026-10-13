package com.kista.api.benchmark.adapter.in.redis;

import com.kista.api.benchmark.application.usecase.BenchmarkUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TradeEventBenchmarkCacheInvalidatorTest {

    @Mock RedisMessageListenerContainer listenerContainer;
    @Mock BenchmarkUseCase benchmarkUseCase;

    private TradeEventBenchmarkCacheInvalidator invalidator() {
        return new TradeEventBenchmarkCacheInvalidator(listenerContainer, benchmarkUseCase, new ObjectMapper());
    }

    @Test
    void trade_event_수신시_해당_사용자의_벤치마크_캐시를_비운다() {
        UUID userId = UUID.randomUUID();
        // trading-core RedisTradeEventPublisher가 발행하는 TradeEventEnvelope shape
        String payload = "{\"userId\":\"" + userId + "\",\"event\":{\"kind\":\"BUY\",\"ticker\":\"SOXL\"}}";

        invalidator().onMessage(new DefaultMessage("trade.event".getBytes(), payload.getBytes(StandardCharsets.UTF_8)), null);

        verify(benchmarkUseCase).invalidateUserCache(userId);
    }

    @Test
    void 역직렬화_실패는_삼키고_무효화하지_않는다() {
        invalidator().onMessage(new DefaultMessage("trade.event".getBytes(), "not-json".getBytes()), null);

        verify(benchmarkUseCase, never()).invalidateUserCache(any());
    }
}
