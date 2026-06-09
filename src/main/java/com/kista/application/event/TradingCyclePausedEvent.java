package com.kista.application.event;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.user.User;

// 전략 중지 이벤트 — 트랜잭션 커밋 후 텔레그램 알림 발송
public record TradingCyclePausedEvent(User user, Account account, Strategy strategy) {}
