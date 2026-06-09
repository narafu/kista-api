package com.kista.domain.model.strategy;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.user.User;

// 스케줄러에서 사이클별 실행에 필요한 컨텍스트 묶음
// strategy: 전략 설정 / currentCycle: 현재 StrategyCycle (initialUsdDeposit 보유)
public record BatchContext(Strategy strategy, StrategyCycle currentCycle, Account account, User user) {}
