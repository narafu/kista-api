// trading 모듈의 공개 계약 일부 — 매매 알림 이벤트 12종 + stats 캐시 무효화용 StrategyCapitalChanged(BatchInterrupted/CycleCompleted/CycleEnded/DcaRoundSkipped/InsufficientBalance/MarketClose/MarketClosed/MarketOpen/NewCycleStarted/OrderCancelFailed/TradingError/TradingReportReady).
// StrategyCapitalChanged는 stats만 구독하고(사용자 캐시 무효화), 나머지 12종은 notify가 구독한다(TradingReportReady는 stats도 캐시 무효화용으로 구독). "event" 이름으로 공개된다.
@org.springframework.modulith.NamedInterface("event")
package com.kista.tradingcore.trading.application.event;
