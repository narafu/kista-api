package com.kista.trading.application.event;

import java.util.UUID;

// 사용자별 장 개시 알림 (UserNotificationPort.notifyMarketOpen) — MarketEventNotifier가 ACTIVE 사용자마다 1건씩 발행
public record MarketOpenEvent(UUID userId) {}
