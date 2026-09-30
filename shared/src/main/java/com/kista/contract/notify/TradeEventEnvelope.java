package com.kista.contract.notify;

import java.util.UUID;

// Redis Pub/Sub(trade.event 채널) 발행 단위 — 수신 대상 userId + 알림 본문
public record TradeEventEnvelope(
        UUID userId,                // SSE를 받을 사용자
        TradeEventMessage event     // 알림 본문
) {}
