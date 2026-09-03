// trading 모듈의 공개 계약 — CycleCompletedEvent/CycleEndedEvent/NewCycleStartedEvent/OrderCancelFailedEvent/
// TradingReportReadyEvent/TradingErrorEvent/InsufficientBalanceEvent/MarketClosedEvent/MarketOpenEvent/
// MarketCloseEvent/BatchInterruptedEvent. notify 모듈이 @TransactionalEventListener로 구독한다
// (CLOSED↔CLOSED 모듈 간 이벤트 교차). "event" 이름으로 공개된다.
@org.springframework.modulith.NamedInterface("event")
package com.kista.trading.application.event;
