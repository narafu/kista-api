package com.kista.trading.domain.model;

import com.kista.account.domain.model.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.user.domain.model.User;

// 스케쥴러에서 사이클별 실행에 필요한 컨텍스트 묶음
// strategy: 전략 설정 / currentCycle: 현재 StrategyCycle (initialUsdDeposit 보유)
public record BatchContext(Strategy strategy, StrategyCycle currentCycle, Account account, User user) {}
