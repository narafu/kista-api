package com.kista.trading.application.event;

import com.kista.domain.model.account.Account;
import com.kista.broker.domain.model.Execution;
import com.kista.trading.domain.model.TradingReport;
import com.kista.domain.model.user.User;

import java.util.List;

// 매매 리포트/SSE 알림 발행 이벤트 — reportEnabled는 TRADING_ALERT 발송 여부만 제어, SSE는 항상 executions를 순회 발송
public record TradingReportReadyEvent(User user, Account account, TradingReport report, List<Execution> executions, boolean reportEnabled) {}
