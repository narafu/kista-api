// trading 모듈의 공개 계약 일부 — 매매 알림 이벤트 11종(BatchInterrupted/CycleCompleted/CycleEnded/InsufficientBalance/MarketClose/MarketClosed/MarketOpen/NewCycleStarted/OrderCancelFailed/TradingError/TradingReportReady).
// notify가 구독한다(TradingReportReady는 stats도 사용자 캐시 무효화용으로 구독). "event" 이름으로 공개된다.
@org.springframework.modulith.NamedInterface("event")
package com.kista.tradingcore.trading.application.event;
