package com.kista.trading.application.event;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.user.User;

import java.math.BigDecimal;

// 새 사이클 시작 이벤트 — 발행처 트랜잭션 유무와 무관하게 리스너에서 알림 채널 라우팅 처리
public record NewCycleStartedEvent(User user, Account account, Strategy strategy, BigDecimal initialUsdDeposit) {}
