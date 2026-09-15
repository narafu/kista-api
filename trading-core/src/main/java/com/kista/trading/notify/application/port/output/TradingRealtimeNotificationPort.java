package com.kista.trading.notify.application.port.output;

import com.kista.trading.notify.domain.model.TradeEventView;

import java.util.UUID;

// 체결 건별 SSE 실시간 알림 — root RealtimeNotificationPort의 trading-core 판(notifyTrade만).
// 구현체(Redis Pub/Sub 기반 RedisTradeEventPublisher)는 후속 태스크가 채운다 — 지금은 인터페이스만 존재.
public interface TradingRealtimeNotificationPort {
    void notifyTrade(UUID userId, TradeEventView event);
}
