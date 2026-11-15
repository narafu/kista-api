package com.kista.tradingcore.notify.application.port.output;

import com.kista.shared.contract.notify.TradeEventMessage;

import java.util.UUID;

// 체결 건별 SSE 실시간 알림 — root RealtimeNotificationPort의 trading-core 판(notifyTrade만).
// 구현체는 Redis Pub/Sub 기반 RedisTradeEventPublisher.
public interface TradingRealtimeNotificationPort {
    void notifyTrade(UUID userId, TradeEventMessage event);
}
