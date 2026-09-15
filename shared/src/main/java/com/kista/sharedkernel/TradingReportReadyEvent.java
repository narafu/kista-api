package com.kista.sharedkernel;

import java.util.List;
import java.util.UUID;

// 매매 리포트/SSE 알림 발행 이벤트 — reportEnabled는 TRADING_ALERT 발송 여부만 제어, SSE는 항상 executions를 순회 발송
// notify 리스너가 재조회 없이 바로 소비할 수 있도록 Account 대신 accountNickname, List<Execution> 대신 TradeLegSummary를 담는다
public record TradingReportReadyEvent(UUID userId, UUID accountId, String accountNickname,
                                       TradingReport report, List<TradeLegSummary> executions, boolean reportEnabled) {}
