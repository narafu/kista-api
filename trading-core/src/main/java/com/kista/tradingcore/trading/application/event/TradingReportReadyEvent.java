package com.kista.tradingcore.trading.application.event;

import com.kista.shared.kernel.TradeLegSummary;
import com.kista.tradingcore.trading.domain.model.TradingReport;

import java.util.List;
import java.util.UUID;

// 매매 리포트/SSE 알림 발행 이벤트 — reportEnabled는 TRADING_ALERT 발송 여부만 제어, SSE는 항상 executions를 순회 발송
// notify 리스너가 재조회 없이 바로 소비할 수 있도록 Account 대신 accountNickname, List<Execution> 대신 TradeLegSummary를 담는다
// strategyId는 SSE 이벤트 대상 전략 식별용 — 필드 추가 전 저장된 EPR 행은 역직렬화 시 null
public record TradingReportReadyEvent(UUID userId, UUID accountId, UUID strategyId, String accountNickname,
                                       TradingReport report, List<TradeLegSummary> executions, boolean reportEnabled) {}
