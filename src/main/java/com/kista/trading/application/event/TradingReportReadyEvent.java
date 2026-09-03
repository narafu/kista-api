package com.kista.trading.application.event;

import com.kista.broker.domain.model.Execution;
import com.kista.trading.domain.model.TradingReport;

import java.util.List;
import java.util.UUID;

// 매매 리포트/SSE 알림 발행 이벤트 — reportEnabled는 TRADING_ALERT 발송 여부만 제어, SSE는 항상 executions를 순회 발송
public record TradingReportReadyEvent(UUID userId, UUID accountId, TradingReport report,
                                       List<Execution> executions, boolean reportEnabled) {}
