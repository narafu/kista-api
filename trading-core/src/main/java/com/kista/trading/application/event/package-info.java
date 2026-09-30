// trading 모듈의 공개 계약 일부 — 매매 알림 이벤트 11종(BatchInterrupted/CycleCompleted/CycleEnded/InsufficientBalance/MarketClose/MarketClosed/MarketOpen/NewCycleStarted/OrderCancelFailed/TradingError/TradingReportReady).
// tradingnotify가 구독한다. "event" 이름으로 공개된다.
@org.springframework.modulith.NamedInterface("event")
package com.kista.trading.application.event;
