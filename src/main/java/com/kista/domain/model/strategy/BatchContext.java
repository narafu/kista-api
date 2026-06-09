package com.kista.domain.model.strategy;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.user.User;

public record BatchContext(TradingCycle cycle, Account account, User user) {}
