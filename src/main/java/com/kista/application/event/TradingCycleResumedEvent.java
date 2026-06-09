package com.kista.application.event;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.user.User;

// 전략 재개 이벤트 — 트랜잭션 커밋 후 텔레그램 알림 발송
public record TradingCycleResumedEvent(User user, Account account, Strategy strategy) {}
