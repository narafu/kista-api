package com.kista.trading.application.event;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.user.User;

// 관리자 수동 체결 보정으로 사이클이 종료됨 — 트랜잭션 커밋 후에만 발행됨 (사용자 알림용)
public record CycleEndedEvent(User user, Account account, Strategy strategy) {}
