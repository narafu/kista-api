package com.kista.domain.port.in;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.user.User;

public interface ExecuteTradingUseCase {
    void execute(Strategy strategy, Account account, User user) throws InterruptedException;
}
